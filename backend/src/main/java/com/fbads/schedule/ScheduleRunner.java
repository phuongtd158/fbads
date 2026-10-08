package com.fbads.schedule;

import com.fbads.client.RateLimits;
import com.fbads.dto.AdObject;
import com.fbads.engine.ActCtx;
import com.fbads.engine.ActResult;
import com.fbads.engine.Action;
import com.fbads.engine.ActionExecutor;
import com.fbads.engine.BudgetMode;
import com.fbads.engine.EngineClock;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogAction;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogError;
import com.fbads.entity.LogKind;
import com.fbads.entity.LogTarget;
import com.fbads.service.EngineState;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookObjects;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Lịch bật/tắt/ngân sách: tới giờ thì chạy, mỗi mốc đúng 1 lần/ngày, trễ tối đa 10 phút vẫn chạy bù. */
@Service
public class ScheduleRunner {
    public static final int GRACE_MIN = 10;
    static final long WRITE_GAP_MS = 300; // giãn nhịp giữa các lần đổi của lịch theo điều kiện (đỡ chạm giới hạn số lần gọi)
    private static final String RATE_LIMITED = "Facebook đang giới hạn số lần gọi (rate limit).";

    /** Kết quả chạy một lịch: BLOCKED = Facebook đang giới hạn và lịch CHƯA làm gì (thử lại lượt sau) */
    public enum RunResult { DONE, BLOCKED }

    /** Một lần chạy trong ngày: lịch khung giờ có 1 lần bật + 1 lần tắt; giờ tắt ≤ giờ bật = tắt sau nửa đêm (thuộc ngày hôm trước) */
    public record Event(String time, ScheduleAction action, boolean prevDay) {}

    private final ScheduleRepository schedules;
    private final FacebookObjects objects;
    private final RateLimits limits;
    private final ActionExecutor executor;
    private final EngineState state;
    private final EngineClock clock;
    private final SettingsService settings;

    public ScheduleRunner(ScheduleRepository schedules, FacebookObjects objects, RateLimits limits,
                          ActionExecutor executor, EngineState state, EngineClock clock, SettingsService settings) {
        this.schedules = schedules;
        this.objects = objects;
        this.limits = limits;
        this.executor = executor;
        this.state = state;
        this.clock = clock;
        this.settings = settings;
    }

    public static List<String> times(Schedule s) {
        return s.getTimes() != null && !s.getTimes().isEmpty() ? s.getTimes() : s.getTime() == null ? List.of() : List.of(s.getTime());
    }

    public static List<Event> events(Schedule s) {
        if (s.getAction() == ScheduleAction.WINDOW) {
            String on = s.windowOn(), off = s.windowOff();
            if (on == null || off == null) return List.of();
            return List.of(new Event(on, ScheduleAction.ON, false), new Event(off, ScheduleAction.OFF, off.compareTo(on) <= 0));
        }
        return times(s).stream().map(t -> new Event(t, s.getAction(), false)).toList();
    }

    /** Lịch khung giờ lúc này đang trong giờ bật không (nút Chạy ngay đưa camp về đúng trạng thái của khung giờ) */
    public static boolean windowIsOn(Schedule s, EngineClock.Now now) {
        int on = EngineClock.toMin(s.windowOn()), off = EngineClock.toMin(s.windowOff());
        List<Integer> days = s.getDays();
        int prev = (now.day() + 6) % 7;
        if (off > on) return days.contains(now.day()) && now.minutes() >= on && now.minutes() < off;
        return (days.contains(now.day()) && now.minutes() >= on) || (days.contains(prev) && now.minutes() < off);
    }

    public void tick() {
        EngineClock.Now now = clock.now();
        for (Schedule sch : schedules.findAllByOrderBySeqAsc()) {
            if (!sch.isEnabled()) continue;
            for (Event ev : events(sch)) {
                if (!sch.getDays().contains(ev.prevDay() ? (now.day() + 6) % 7 : now.day())) continue;
                int at = EngineClock.toMin(ev.time());
                String key = sch.getId() + ":" + now.date() + ":" + ev.time();
                if (now.minutes() < at || now.minutes() - at > GRACE_MIN || state.hasRun(key)) continue;
                // Giữ chỗ TRƯỚC khi chạy (khoá chính trong DB): bản app khác đã giữ thì thôi
                if (!state.claimRun(key, now.date())) continue;
                if (run(sch, sch.getAction() == ScheduleAction.WINDOW ? ev.action() : null) == RunResult.BLOCKED) {
                    // Facebook đang giới hạn và lịch chưa làm gì: thử lại ở lượt sau trong thời gian chạy bù; hết thời gian thì ghi lỗi
                    if (now.minutes() - at < GRACE_MIN) state.releaseRun(key);
                    else {
                        LogEntry e = entry(sch, "-");
                        e.setOk(false);
                        e.setDetail("Không chạy được lượt " + ev.time() + ": Facebook giới hạn số lần gọi suốt " + GRACE_MIN
                                + " phút sau giờ hẹn.");
                        e.setError(LogError.of(RATE_LIMITED));
                        executor.record(e, false);
                    }
                }
            }
        }
        state.cleanupRuns(now.date());
    }

    /**
     * Chạy một lịch ngay. turn: ON | OFF cho lần chạy của lịch khung giờ; null (bấm Chạy ngay) thì theo khung giờ lúc này.
     * Trả BLOCKED nếu Facebook đang giới hạn và CHƯA làm gì.
     */
    public RunResult run(Schedule sch, ScheduleAction turn) {
        List<AdObject> objs = objects.listObjects(true);
        if (limits.blocked()) return RunResult.BLOCKED;
        if (sch.getAction() == ScheduleAction.WINDOW && turn == null)
            turn = windowIsOn(sch, clock.now()) ? ScheduleAction.ON : ScheduleAction.OFF;
        Action action = actionOf(sch, turn != null ? turn : sch.getAction());
        String source = "Lịch: " + sch.getName();
        ActCtx ctx = ActCtx.of(LogKind.SCHEDULE, sch.getId(), sch.getName());
        if ("filter".equals(sch.getTargetMode())) { runFilter(sch, objs, action, source, ctx); return RunResult.DONE; }
        List<String> targets = sch.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            String id = targets.get(i);
            if (i > 0 && limits.blocked()) { stopLog(sch, targets.size() - i); break; }
            AdObject obj = objs.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
            if (obj == null) {
                LogEntry e = entry(sch, id);
                e.setDetail("Không tìm thấy đối tượng");
                e.setOk(false);
                e.setTarget(LogTarget.idOnly(id));
                e.setAction(LogAction.of(action));
                e.setError(LogError.of("Không tìm thấy đối tượng trên tài khoản quảng cáo (có thể đã bị xoá hoặc đổi cấp)."));
                executor.record(e, false);
                continue;
            }
            executor.act(obj, action, source, ctx);
        }
        return RunResult.DONE;
    }

    /** Hành động lên từng camp của lịch: lịch khung giờ thì bật hoặc tắt tuỳ lượt */
    private static Action actionOf(Schedule sch, ScheduleAction what) {
        return switch (what) {
            case ON -> Action.on();
            case OFF -> Action.off();
            case WINDOW -> throw new IllegalArgumentException("Lịch khung giờ: cần biết lượt này bật hay tắt");
            // mã lạ (dữ liệu cũ) xử lý như "đặt số", giống bản Node
            case BUDGET -> Action.budget(Objects.requireNonNullElse(BudgetMode.from(sch.getMode()), BudgetMode.SET), sch.getValue(),
                    sch.getMax() == null ? 0 : sch.getMax(), sch.getMin() == null ? 0 : sch.getMin());
        };
    }

    private void stopLog(Schedule sch, int left) {
        LogEntry e = entry(sch, "-");
        e.setOk(false);
        e.setDetail("Dừng giữa chừng: Facebook đang giới hạn số lần gọi, còn " + left + " mục chưa xử lý ở lượt này.");
        e.setError(LogError.of(RATE_LIMITED));
        executor.record(e, false);
    }

    /** Dòng nhật ký của lịch (chưa có kết quả): nguồn "Lịch: tên lịch", chế độ chạy hiện tại */
    private LogEntry entry(Schedule sch, String name) {
        LogEntry e = LogEntry.of(LogKind.SCHEDULE, "Lịch: " + sch.getName(), name);
        e.setRefId(sch.getId());
        e.setRefName(sch.getName());
        e.setMode(settings.get().mode());
        return e;
    }

    /**
     * Lịch "Theo điều kiện": lọc lại theo số liệu lúc chạy. Mỗi mục vẫn ghi nhật ký riêng (hoàn tác được) nhưng không gửi Telegram
     * từng mục; cuối lượt ghi 1 dòng tóm tắt (và 1 tin Telegram nếu có thay đổi/lỗi).
     */
    private void runFilter(Schedule sch, List<AdObject> objs, Action action, String source, ActCtx ctx) {
        ScheduleFilter f = sch.getFilter() == null ? new ScheduleFilter(null, null, null, null, null, null, null, null)
                : sch.getFilter();
        Set<String> ex = new HashSet<>(sch.getExclude() == null ? List.of() : sch.getExclude());
        List<AdObject> list = new ArrayList<>(BulkFilter.match(objs, f).stream().filter(o -> !ex.contains(o.id())).toList());
        if (action.isBudget()) list.removeIf(o -> o.dailyBudget() == null); // CBO: không có ngân sách ở cấp này → không áp dụng
        String desc = f.describe() + (ex.isEmpty() ? "" : " · trừ " + ex.size() + " mục");
        int ok = 0, fail = 0, same = 0, left = 0;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0 && limits.blocked()) { left = list.size() - i; break; }
            ActResult r = executor.act(list.get(i), action, source, ctx.silenced());
            switch (r) {
                case OK -> ok++;
                case FAIL, ERROR -> fail++;
                case NOOP, SKIP -> same++;
            }
            if ((r == ActResult.OK || r == ActResult.FAIL) && i < list.size() - 1) sleep(WRITE_GAP_MS);
        }
        AppSettings s = settings.get();
        boolean dry = s.isDry();
        String detail = list.isEmpty() ? "Không có mục nào khớp điều kiện (" + desc + ")."
                : "Khớp " + list.size() + " mục (" + desc + "): " + (dry ? "sẽ đổi" : "đã đổi") + " " + ok
                + (same > 0 ? ", đã đúng sẵn/bỏ qua " + same : "") + (fail > 0 ? ", lỗi " + fail : "")
                + (left > 0 ? ". Dừng vì Facebook giới hạn số lần gọi, còn " + left + " mục chưa xử lý" : "") + ".";
        LogEntry e = entry(sch, desc);
        e.setDry(dry);
        e.setOk(fail == 0 && left == 0);
        e.setAction(LogAction.of(action));
        e.setDetail(detail);
        if (fail > 0 || left > 0) e.setError(LogError.of(left > 0 ? RATE_LIMITED
                : fail + " mục lỗi, xem các dòng nhật ký của lịch này."));
        executor.record(e, ok == 0 && fail == 0 && left == 0);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
