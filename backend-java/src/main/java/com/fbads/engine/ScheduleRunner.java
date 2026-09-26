package com.fbads.engine;

import com.fbads.automation.Schedule;
import com.fbads.automation.ScheduleRepository;
import com.fbads.engine.state.EngineState;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.BulkFilter;
import com.fbads.facebook.FacebookService;
import com.fbads.facebook.RateLimits;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lịch bật/tắt/ngân sách: tới giờ thì chạy, mỗi mốc đúng 1 lần/ngày, trễ tối đa 10 phút vẫn chạy bù. */
@Service
public class ScheduleRunner {
    public static final int GRACE_MIN = 10;
    static final long WRITE_GAP_MS = 300; // giãn nhịp giữa các lần đổi của lịch theo điều kiện (đỡ chạm giới hạn số lần gọi)
    public static final String BLOCKED = "blocked";

    /** Một lần chạy trong ngày: lịch khung giờ có 1 lần bật + 1 lần tắt; giờ tắt ≤ giờ bật = tắt sau nửa đêm (thuộc ngày hôm trước) */
    public record Event(String time, String action, boolean prevDay) {}

    private final ScheduleRepository schedules;
    private final FacebookService fb;
    private final RateLimits limits;
    private final ActionExecutor executor;
    private final EngineState state;
    private final EngineClock clock;
    private final SettingsService settings;

    public ScheduleRunner(ScheduleRepository schedules, FacebookService fb, RateLimits limits, ActionExecutor executor, EngineState state,
                          EngineClock clock, SettingsService settings) {
        this.schedules = schedules;
        this.fb = fb;
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
        if ("window".equals(s.getAction())) {
            String on = s.windowOn(), off = s.windowOff();
            if (on == null || off == null) return List.of();
            return List.of(new Event(on, "on", false), new Event(off, "off", off.compareTo(on) <= 0));
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
                if (BLOCKED.equals(run(sch, "window".equals(sch.getAction()) ? ev.action() : null))) {
                    // Facebook đang giới hạn và lịch chưa làm gì: thử lại ở lượt sau trong thời gian chạy bù; hết thời gian thì ghi lỗi
                    if (now.minutes() - at < GRACE_MIN) state.releaseRun(key);
                    else executor.record(false, e -> {
                        e.setKind("schedule"); e.setRefId(sch.getId()); e.setRefName(sch.getName()); e.setSource("Lịch: " + sch.getName()); e.setName("-");
                        e.setMode(settings.get().mode()); e.setOk(false);
                        e.setDetail("Không chạy được lượt " + ev.time() + ": Facebook giới hạn số lần gọi suốt " + GRACE_MIN + " phút sau giờ hẹn.");
                        e.setError(Map.of("message", "Facebook đang giới hạn số lần gọi (rate limit)."));
                    });
                }
            }
        }
        state.cleanupRuns(now.date());
    }

    /**
     * Chạy một lịch ngay. type: 'on' | 'off' cho lần chạy của lịch khung giờ; null (bấm Chạy ngay) thì theo khung giờ lúc này.
     * Trả "blocked" nếu Facebook đang giới hạn và CHƯA làm gì.
     */
    public String run(Schedule sch, String type) {
        List<AdObject> objs = fb.listObjects(true);
        if (limits.blocked()) return BLOCKED;
        if ("window".equals(sch.getAction()) && type == null) type = windowIsOn(sch, clock.now()) ? "on" : "off";
        Action action = new Action(type != null ? type : sch.getAction(), sch.getMode(), sch.getValue(),
                sch.getMax() == null ? 0 : sch.getMax(), sch.getMin() == null ? 0 : sch.getMin(), null);
        String source = "Lịch: " + sch.getName();
        ActCtx ctx = ActCtx.of("schedule", sch.getId(), sch.getName());
        if ("filter".equals(sch.getTargetMode())) { runFilter(sch, objs, action, source, ctx); return null; }
        List<String> targets = sch.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            String id = targets.get(i);
            if (i > 0 && limits.blocked()) { stopLog(sch, source, targets.size() - i); break; }
            AdObject obj = objs.stream().filter(o -> o.id.equals(id)).findFirst().orElse(null);
            if (obj == null) {
                Action a = action;
                executor.record(false, e -> {
                    e.setKind("schedule"); e.setRefId(sch.getId()); e.setRefName(sch.getName()); e.setSource(source); e.setName(id);
                    e.setDetail("Không tìm thấy đối tượng"); e.setOk(false); e.setMode(settings.get().mode()); e.setTarget(Map.of("id", id));
                    Map<String, Object> aj = new LinkedHashMap<>();
                    aj.put("type", a.type()); aj.put("mode", sch.getMode()); aj.put("value", sch.getValue());
                    e.setAction(aj);
                    e.setError(Map.of("message", "Không tìm thấy đối tượng trên tài khoản quảng cáo (có thể đã bị xoá hoặc đổi cấp)."));
                });
                continue;
            }
            executor.act(obj, action, source, ctx);
        }
        return null;
    }

    private void stopLog(Schedule sch, String source, int left) {
        executor.record(false, e -> {
            e.setKind("schedule"); e.setRefId(sch.getId()); e.setRefName(sch.getName()); e.setSource(source); e.setName("-");
            e.setMode(settings.get().mode()); e.setOk(false);
            e.setDetail("Dừng giữa chừng: Facebook đang giới hạn số lần gọi, còn " + left + " mục chưa xử lý ở lượt này.");
            e.setError(Map.of("message", "Facebook đang giới hạn số lần gọi (rate limit)."));
        });
    }

    /**
     * Lịch "Theo điều kiện": lọc lại theo số liệu lúc chạy. Mỗi mục vẫn ghi nhật ký riêng (hoàn tác được) nhưng không gửi Telegram
     * từng mục; cuối lượt ghi 1 dòng tóm tắt (và 1 tin Telegram nếu có thay đổi/lỗi).
     */
    private void runFilter(Schedule sch, List<AdObject> objs, Action action, String source, ActCtx ctx) {
        Map<String, Object> f = sch.getFilter() == null ? Map.of() : sch.getFilter();
        Set<String> ex = new HashSet<>(sch.getExclude() == null ? List.of() : sch.getExclude());
        List<AdObject> list = new ArrayList<>(BulkFilter.match(objs, f).stream().filter(o -> !ex.contains(o.id)).toList());
        if (action.isBudget()) list.removeIf(o -> o.dailyBudget == null); // CBO: không có ngân sách ở cấp này → không áp dụng
        String desc = BulkFilter.describe(f) + (ex.isEmpty() ? "" : " · trừ " + ex.size() + " mục");
        int ok = 0, fail = 0, same = 0, left = 0;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0 && limits.blocked()) { left = list.size() - i; break; }
            String r = executor.act(list.get(i), action, source, ctx.silenced());
            if (r.equals("ok")) ok++;
            else if (r.equals("fail") || r.equals("error")) fail++;
            else same++;
            if ((r.equals("ok") || r.equals("fail")) && i < list.size() - 1) sleep(WRITE_GAP_MS);
        }
        AppSettings s = settings.get();
        boolean dry = s.isDry();
        String detail = list.isEmpty() ? "Không có mục nào khớp điều kiện (" + desc + ")."
                : "Khớp " + list.size() + " mục (" + desc + "): " + (dry ? "sẽ đổi" : "đã đổi") + " " + ok
                + (same > 0 ? ", đã đúng sẵn/bỏ qua " + same : "") + (fail > 0 ? ", lỗi " + fail : "")
                + (left > 0 ? ". Dừng vì Facebook giới hạn số lần gọi, còn " + left + " mục chưa xử lý" : "") + ".";
        int fOk = ok, fFail = fail, fLeft = left;
        Map<String, Object> aj = new LinkedHashMap<>();
        aj.put("type", action.type());
        if (action.isBudget()) { aj.put("mode", action.mode()); aj.put("value", action.value()); }
        executor.record(fOk == 0 && fFail == 0 && fLeft == 0, e -> {
            e.setKind("schedule"); e.setRefId(sch.getId()); e.setRefName(sch.getName()); e.setSource(source); e.setName(desc);
            e.setMode(s.mode()); e.setDry(dry); e.setOk(fFail == 0 && fLeft == 0); e.setAction(aj); e.setDetail(detail);
            if (fFail > 0 || fLeft > 0) e.setError(Map.of("message", fLeft > 0 ? "Facebook đang giới hạn số lần gọi (rate limit)."
                    : fFail + " mục lỗi, xem các dòng nhật ký của lịch này."));
        });
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
