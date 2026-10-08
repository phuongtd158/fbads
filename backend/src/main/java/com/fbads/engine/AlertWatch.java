package com.fbads.engine;

import com.fbads.client.FbException;
import com.fbads.client.RateLimits;
import com.fbads.common.Fmt;
import com.fbads.entity.AppSettings;
import com.fbads.event.EventBus;
import com.fbads.notify.Notice;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.WsState;
import com.fbads.service.facebook.FacebookHealth.DisapprovedAd;
import com.fbads.service.facebook.FacebookHealth;
import com.fbads.service.facebook.FacebookInsights.HourSpend;
import com.fbads.service.facebook.FacebookInsights;
import com.fbads.service.facebook.FacebookObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cảnh báo bất thường (bản Java của lib/alerts.js), 30 phút kiểm tra một lần, mỗi sự việc chỉ báo một lần, gửi Telegram và ghi nhật ký:
 *  - tài khoản quảng cáo có vấn đề (bị vô hiệu hoá, nợ thanh toán…) và khi hoạt động lại
 *  - quảng cáo bị từ chối (báo mỗi quảng cáo một lần; được duyệt rồi bị từ chối lại thì báo lại)
 *  - chi tiêu hôm nay tính đến giờ này cao hơn X% so với cùng giờ hôm qua (mỗi tài khoản tối đa 1 lần/ngày)
 * Chỉ chạy khi kết nối Facebook thật (dữ liệu giả không có các thông tin này). Đã báo gì lưu theo workspace (WsState "alerts").
 */
@Component
public class AlertWatch {
    private static final Logger log = LoggerFactory.getLogger(AlertWatch.class);
    public static final long EVERY_MS = 30 * 60_000;
    static final int MAX_LINES = 10; // tin Telegram liệt kê tối đa 10 quảng cáo, còn lại ghi "và N quảng cáo khác"

    /** Đã báo gì: acc = trạng thái tài khoản lần trước, ads = quảng cáo bị từ chối đã báo, spike = ngày đã báo tăng vọt */
    public record State(Map<String, Integer> acc, Map<String, List<String>> ads, Map<String, String> spike) {
        public State {
            acc = acc == null ? new LinkedHashMap<>() : new LinkedHashMap<>(acc);
            ads = ads == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ads);
            spike = spike == null ? new LinkedHashMap<>() : new LinkedHashMap<>(spike);
        }
    }

    public record Spike(int hour, double now, double before, long up) {}

    private final SettingsService settings;
    private final FacebookObjects objects;
    private final FacebookInsights insights;
    private final FacebookHealth health;
    private final RateLimits limits;
    private final LogService logs;
    private final EventBus events;
    private final WsState state;
    private final EngineClock clock;
    /** Lần kiểm tra gần nhất của mỗi workspace. Không lưu: khởi động lại thì kiểm tra ngay lượt đầu */
    private final Map<Long, Long> lastAt = new ConcurrentHashMap<>();

    public AlertWatch(SettingsService settings, FacebookObjects objects, FacebookInsights insights,
            FacebookHealth health, RateLimits limits, LogService logs, EventBus events, WsState state, EngineClock clock) {
        this.settings = settings;
        this.objects = objects;
        this.insights = insights;
        this.health = health;
        this.limits = limits;
        this.logs = logs;
        this.events = events;
        this.state = state;
        this.clock = clock;
    }

    static String esc(String t) { return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private void alert(String name, String detail, String text, boolean ok) {
        String mode = settings.get().mode();
        logs.log(l -> {
            l.setKind("system");
            l.setSource("Cảnh báo");
            l.setName(name);
            l.setDetail(detail);
            l.setOk(ok);
            l.setMode(mode);
        });
        events.notify("alerts", new Notice(Notice.Topic.ALERT, text));
    }

    /**
     * Chi tiêu hôm nay so với cùng giờ hôm qua. Giờ hiện tại = giờ muộn nhất đã có chi tiêu hôm nay (theo múi giờ của tài khoản),
     * hôm qua tính tới hết giờ đó (tính dư một phần giờ hiện tại, nên chỉ báo khi tăng thật). null = không tăng vọt.
     */
    public static Spike spikeOf(List<HourSpend> today, List<HourSpend> yesterday, double pct, double minSpend) {
        if (today.isEmpty()) return null;
        int hour = today.stream().mapToInt(HourSpend::hour).max().orElse(0);
        double now = today.stream().mapToDouble(HourSpend::spend).sum();
        double before = yesterday.stream().filter(r -> r.hour() <= hour).mapToDouble(HourSpend::spend).sum();
        if (now < minSpend || before <= 0 || now <= before * (1 + pct / 100)) return null;
        return new Spike(hour, now, before, Math.round((now / before - 1) * 100));
    }

    /** Mỗi lượt, trong workspace hiện tại */
    public void tick() {
        AppSettings s = settings.get();
        if (s.isMock() || s.getAccessToken() == null || s.getAccessToken().isEmpty()
                || (!s.isAlertAccount() && !s.isAlertDisapproved() && !s.isAlertSpike())) return;
        long ws = WorkspaceContext.require();
        Long last = lastAt.get(ws);
        if (last != null && clock.millis() - last < EVERY_MS) return;
        if (limits.blocked()) return; // Facebook đang giới hạn số lần gọi: để lượt sau
        lastAt.put(ws, clock.millis());
        State st = state.get("alerts", State.class, new State(null, null, null));
        String today = clock.now().date();
        for (String id : s.accountIds()) {
            try {
                checkAccount(id, st, s, today);
            } catch (RuntimeException e) {
                if (FbException.isRateLimited(e)) break; // các tài khoản còn lại để lượt sau
                log.error("Lỗi kiểm tra cảnh báo (tài khoản {}): {}", id, e.getMessage());
            }
        }
        state.put("alerts", st);
    }

    private void checkAccount(String id, State st, AppSettings s, String today) {
        FacebookHealth.AccountHealth h = health.accountHealth(id);
        String nm = h.name();
        String cur = objects.accounts().stream()
                .filter(a -> id.equals(a.get("id")))
                .map(a -> String.valueOf(a.get("currency")))
                .findFirst().orElse("");

        if (s.isAlertAccount()) {
            Integer prev = st.acc().get(id);
            if (!h.active() && (prev == null || prev != h.status())) {
                alert(nm, "Tài khoản quảng cáo đang ở trạng thái: " + h.statusText() + ".",
                        "🚫 <b>Tài khoản quảng cáo có vấn đề</b>\n" + esc(nm) + " (" + esc(id) + "): <b>" + esc(h.statusText()) + "</b>.\n"
                                + "Quảng cáo trong tài khoản này có thể đã ngừng chạy. Kiểm tra trong Trình quản lý quảng cáo.", false);
            } else if (h.active() && prev != null && prev != 1) {
                alert(nm, "Tài khoản quảng cáo đã hoạt động lại.",
                        "✅ <b>Tài khoản quảng cáo đã hoạt động lại</b>\n" + esc(nm) + " (" + esc(id) + ").", true);
            }
        }
        st.acc().put(id, h.status());

        if (s.isAlertDisapproved()) {
            List<DisapprovedAd> ads = health.disapprovedAds(id);
            Set<String> seen = new HashSet<>(st.ads().getOrDefault(id, List.of()));
            List<DisapprovedAd> fresh = ads.stream().filter(a -> !seen.contains(a.id())).toList();
            // được duyệt lại thì bỏ khỏi danh sách → bị từ chối lần nữa sẽ báo lại
            st.ads().put(id, new ArrayList<>(ads.stream().map(DisapprovedAd::id).toList()));
            if (!fresh.isEmpty()) {
                List<String> lines = fresh.stream().limit(MAX_LINES).map(a -> "• " + esc(a.name()) + " (camp " + esc(a.campaign())
                        + (a.adset().isEmpty() ? "" : ", nhóm " + esc(a.adset())) + ")"
                                + (a.reason().isEmpty() ? "" : ": " + esc(a.reason()))).toList();
                String more = fresh.size() > MAX_LINES ? "\n…và " + (fresh.size() - MAX_LINES) + " quảng cáo khác" : "";
                alert(nm, fresh.size() + " quảng cáo bị từ chối: "
                        + String.join(", ", fresh.stream().limit(5).map(DisapprovedAd::name).toList()) + (fresh.size() > 5 ? "…" : ""),
                        "⛔ <b>" + fresh.size() + " quảng cáo bị từ chối</b> ở " + esc(nm) + "\n" + String.join("\n", lines) + more, false);
            }
        }

        if (s.isAlertSpike() && !today.equals(st.spike().get(id))) {
            List<HourSpend> t = insights.hourlySpend(id, "today");
            if (!t.isEmpty()) {
                Spike sp = spikeOf(t, insights.hourlySpend(id, "yesterday"), s.getSpikePct() > 0 ? s.getSpikePct() : 50,
                        s.getSpikeMinSpend());
                if (sp != null) {
                    st.spike().put(id, today);
                    String until = String.format("%02d:00", sp.hour() + 1);
                    alert(nm, "Chi tiêu hôm nay tới " + until + " là " + Fmt.money(sp.now()) + " " + cur + ", cao hơn " + sp.up()
                            + "% so với cùng giờ hôm qua ("
                                    + Fmt.money(sp.before()) + " " + cur + ").",
                            "📈 <b>Chi tiêu tăng vọt</b> ở " + esc(nm) + "\nHôm nay tới " + until + ": <b>" + Fmt.money(sp.now())
                                    + " " + esc(cur) + "</b>, cao hơn "
                                    + sp.up() + "% so với cùng giờ hôm qua (" + Fmt.money(sp.before()) + " " + esc(cur) + ").", false);
                }
            }
        }
    }

    /** Quên lần kiểm tra gần nhất của workspace hiện tại (lượt sau kiểm tra ngay) */
    public void reset() { lastAt.remove(WorkspaceContext.require()); }
}
