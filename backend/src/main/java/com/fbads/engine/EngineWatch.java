package com.fbads.engine;

import com.fbads.client.FbException;
import com.fbads.common.Hash;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogKind;
import com.fbads.event.EventBus;
import com.fbads.notify.Notice;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.WsState;
import com.fbads.service.facebook.FacebookAuth;
import com.fbads.service.facebook.FacebookGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canh cho tool chạy 24/7 (bản Java của lib/watch.js): token Facebook sắp hết hạn/hỏng, vòng tự động bị kẹt hoặc lỗi liên tục.
 * Báo qua Telegram và nhật ký của workspace liên quan, mỗi sự việc chỉ báo một lần. /api/health đọc trạng thái ở đây.
 *
 * Khác bản Node: engine chạy lần lượt từng workspace, nên "lỗi liên tiếp" đếm riêng từng workspace và báo cho workspace đó;
 * "bị kẹt" báo cho workspace đang chạy dở. Lúc xong lượt gần nhất lưu ở Redis để mọi bản tool (kể cả bản không giữ khoá
 * engine) trả /api/health giống nhau.
 */
@Component
public class EngineWatch {
    private static final Logger log = LoggerFactory.getLogger(EngineWatch.class);
    public static final int TOKEN_WARN_DAYS = 7;          // còn ≤ 7 ngày thì báo, mỗi ngày một lần tới khi đổi token
    static final long TOKEN_RETRY_MS = 3_600_000;         // kiểm tra token lỗi (mạng, Facebook) → thử lại sau 1 giờ, không gọi mỗi lượt
    public static final long STALL_MS = 5 * 60_000;       // một lượt chạy quá 5 phút, hoặc 5 phút không xong lượt nào → coi là đứng
    static final int ERRORS_ALERT = 3;                    // lỗi 3 lượt liên tiếp thì báo
    /** Lúc xong lượt gần nhất (ms), dùng chung mọi bản tool */
    static final String LAST_DONE = "fbads:engine:last-done";
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    /** Đã báo gì về token hiện tại: fp = mã nhận ra token (không lưu token), bad = đã báo hỏng, date = ngày đã kiểm tra */
    public record TokenAlert(String fp, Boolean bad, String date) {}

    /** Trạng thái của một workspace trong bộ nhớ */
    private static final class Run {
        int errorsInRow;
        String lastError = "";
        boolean errorAlerted, stallAlerted;
        long tokenFailedAt;
    }

    private final SettingsService settings;
    private final FacebookAuth auth;
    private final FacebookGraph graph;
    private final LogService logs;
    private final EventBus events;
    private final WsState state;
    private final EngineClock clock;
    private final StringRedisTemplate redis;
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    private final long startedAt;
    private volatile long busySince;
    private volatile Long busyWs;
    private volatile long lastDoneLocal;

    public EngineWatch(SettingsService settings, FacebookAuth auth, FacebookGraph graph, LogService logs, EventBus events, WsState state,
            EngineClock clock, StringRedisTemplate redis) {
        this.settings = settings;
        this.auth = auth;
        this.graph = graph;
        this.logs = logs;
        this.events = events;
        this.state = state;
        this.clock = clock;
        this.redis = redis;
        this.startedAt = clock.millis();
    }

    private Run run() { return runs.computeIfAbsent(WorkspaceContext.require(), k -> new Run()); }

    /** Ghi nhật ký (nguồn "Hệ thống") và gửi thông báo cho workspace hiện tại */
    void alert(String name, String detail, String text, boolean ok) {
        LogEntry l = LogEntry.of(LogKind.SYSTEM, "Hệ thống", name);
        l.setDetail(detail);
        l.setOk(ok);
        l.setMode(settings.get().mode());
        logs.add(l);
        notify(text);
    }

    void notify(String text) { events.notify("alerts", new Notice(Notice.Topic.ALERT, text)); }

    // ------------------------------------------------------------------ Token Facebook
    /** Mỗi lượt, trong workspace hiện tại. Kiểm tra token mỗi ngày một lần; Facebook vừa báo token hỏng thì báo ngay. */
    public void tickToken() {
        AppSettings s = settings.get();
        if (s.isMock() || s.getAccessToken() == null || s.getAccessToken().isEmpty()) return;
        String fp = Hash.sha256(s.getAccessToken()).substring(0, 12);
        TokenAlert saved = state.get("tokenAlert", TokenAlert.class);
        TokenAlert ta = saved != null && fp.equals(saved.fp()) ? saved : new TokenAlert(fp, false, null); // token mới → báo lại từ đầu
        String err = graph.tokenError();
        if (!err.isEmpty()) { bad(ta, err); return; }
        String today = clock.now().date();
        Run r = run();
        if (today.equals(ta.date()) || clock.millis() - r.tokenFailedAt < TOKEN_RETRY_MS) return;
        Map<String, Object> t;
        try {
            t = auth.inspectToken(s.getAccessToken());
        } catch (FbException e) {
            if (Integer.valueOf(190).equals(e.code())) { bad(new TokenAlert(fp, ta.bad(), today), e.getMessage()); return; }
            r.tokenFailedAt = clock.millis(); // mạng/Facebook lỗi: thử lại sau 1 giờ
            return;
        }
        ta = new TokenAlert(fp, ta.bad(), today);
        state.put("tokenAlert", ta);
        if (!Boolean.TRUE.equals(t.get("valid"))) {
            bad(ta, "Facebook báo token không hợp lệ (có thể đã bị thu hồi hoặc đổi mật khẩu).");
            return;
        }
        if (!(t.get("daysLeft") instanceof Number n) || n.longValue() > TOKEN_WARN_DAYS) return;
        long days = n.longValue();
        String when = days >= 1 ? "Còn " + days + " ngày" : "Hết hạn trong hôm nay";
        String on = SHORT.format(Instant.ofEpochMilli(((Number) t.get("expiresAt")).longValue()).atZone(clock.zone()));
        alert("Token Facebook", "Token sắp hết hạn: " + when.toLowerCase() + " (" + on + ").",
                "⏳ <b>Token Facebook sắp hết hạn</b>\n" + when + " (" + on
                        + "). Vào Cài đặt → Kết nối để tạo token mới, nếu không lịch và rule sẽ ngừng.", false);
    }

    private void bad(TokenAlert ta, String why) {
        if (Boolean.TRUE.equals(ta.bad())) return;
        state.put("tokenAlert", new TokenAlert(ta.fp(), true, ta.date()));
        alert("Token Facebook", "Token không còn dùng được: " + why,
                "❌ <b>Token Facebook không còn dùng được</b>\n" + why
                        + "\nLịch và rule sẽ không chạy được. Vào Cài đặt → Kết nối để dán token mới.", false);
    }

    // ------------------------------------------------------------------ Vòng tự động
    /** Bắt đầu một lượt (mọi workspace) */
    public void tickStarted() { busySince = clock.millis(); }

    /** Bắt đầu chạy workspace hiện tại trong lượt */
    public void workspaceStarted() { busyWs = WorkspaceContext.require(); }

    /**
     * Xong workspace hiện tại: err = lỗi đầu tiên của lượt (null = ổn). Bị Facebook giới hạn số lần gọi thì tool đã tự chờ,
     * không tính là lỗi. Lỗi 3 lượt liên tiếp → báo 1 lần; chạy lại bình thường sau khi đã báo → báo đã chạy lại.
     */
    public void workspaceDone(RuntimeException err) {
        Run r = run();
        boolean wasDown = r.stallAlerted || r.errorAlerted;
        if (err != null && !FbException.isRateLimited(err)) {
            r.errorsInRow++;
            r.lastError = err.getMessage() == null ? err.toString() : err.getMessage();
            if (r.errorsInRow >= ERRORS_ALERT && !r.errorAlerted) {
                r.errorAlerted = true;
                notify("⚠️ <b>Vòng tự động lỗi " + r.errorsInRow + " lượt liên tiếp</b>\n" + r.lastError
                        + "\nLịch và rule có thể không chạy. Xem Nhật ký để biết chi tiết.");
            }
            return;
        }
        r.errorsInRow = 0;
        r.lastError = "";
        r.stallAlerted = false;
        r.errorAlerted = false;
        if (wasDown) alert("Vòng tự động", "Vòng tự động đã chạy lại bình thường.", "✅ <b>Vòng tự động đã chạy lại bình thường</b>", true);
    }

    /** Xong một lượt (mọi workspace) */
    public void tickDone() {
        busySince = 0;
        busyWs = null;
        lastDoneLocal = clock.millis();
        try {
            redis.opsForValue().set(LAST_DONE, Long.toString(lastDoneLocal));
        } catch (RuntimeException e) {
            log.warn("Không ghi được lúc xong lượt vào Redis: {}", e.getMessage());
        }
    }

    /** Lượt đang chạy quá lâu: bộ hẹn giờ riêng mỗi phút (vòng tự động đang kẹt thì không tự báo được) */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void checkStall() {
        try {
            long since = busySince;
            Long ws = busyWs;
            if (since == 0 || ws == null || clock.millis() - since < STALL_MS) return;
            WorkspaceContext.run(ws, () -> {
                Run r = run();
                if (r.stallAlerted) return;
                r.stallAlerted = true;
                long min = Math.round((clock.millis() - since) / 60_000.0);
                alert("Vòng tự động", "Một lượt chạy đã kéo dài " + min + " phút, lịch và rule đang phải chờ.",
                        "⚠️ <b>Vòng tự động bị kẹt</b>\nMột lượt đã chạy " + min + " phút chưa xong, lịch và rule đang phải chờ.", false);
            });
        } catch (RuntimeException e) {
            log.error("Lỗi canh vòng tự động: {}", e.getMessage());
        }
    }

    /** Cho /api/health: ok = vòng tự động còn chạy (xong một lượt trong 5 phút gần nhất, hoặc vừa khởi động) */
    public Map<String, Object> health() {
        long now = clock.millis(), since = busySince;
        long lastDone = lastDoneLocal;
        try {
            String v = redis.opsForValue().get(LAST_DONE);
            if (v != null) lastDone = Math.max(lastDone, Long.parseLong(v));
        } catch (RuntimeException ignored) { /* Redis lỗi: dùng giờ của bản này */ }
        boolean stuck = since != 0 && now - since >= STALL_MS;
        boolean fresh = lastDone != 0 ? now - lastDone < STALL_MS : now - startedAt < STALL_MS;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", !stuck && fresh);
        m.put("lastTickAt", lastDone == 0 ? null : Instant.ofEpochMilli(lastDone).toString());
        return m;
    }

    /** Đặt lại (dùng cho kiểm thử) */
    public void reset() {
        runs.clear();
        busySince = 0;
        busyWs = null;
        lastDoneLocal = 0;
        try { redis.delete(LAST_DONE); } catch (RuntimeException ignored) { /* như trên */ }
    }

    /** Cho kiểm thử: giả lập lượt bắt đầu lúc since */
    void setBusy(long since, Long ws) {
        busySince = since;
        busyWs = ws;
    }
}
