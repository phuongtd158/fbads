package com.fbads.engine;

import com.fbads.common.Fmt;
import com.fbads.engine.state.EngineState;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.FacebookService;
import com.fbads.facebook.FbException;
import com.fbads.logs.LogEntry;
import com.fbads.logs.LogService;
import com.fbads.notify.TelegramService;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lập kế hoạch (plan) và thực thi (act) một hành động lên camp/nhóm QC, tôn trọng chế độ chạy thử,
 * ghi nhật ký và gửi Telegram. Bản Java của plan()/act()/record() trong lib/engine.js.
 */
@Service
public class ActionExecutor {
    private final SettingsService settings;
    private final FacebookService fb;
    private final LogService logs;
    private final TelegramService telegram;
    private final EngineState state;
    private final EngineClock clock;

    public ActionExecutor(SettingsService settings, FacebookService fb, LogService logs, TelegramService telegram, EngineState state, EngineClock clock) {
        this.settings = settings;
        this.fb = fb;
        this.logs = logs;
        this.telegram = telegram;
        this.state = state;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ Nhật ký
    /** Ghi nhật ký (+ Telegram trừ khi silent). */
    public LogEntry record(boolean silent, Consumer<LogEntry> fill) {
        LogEntry saved = logs.log(fill);
        if (!silent) {
            boolean isNotify = saved.getAction() != null && "notify".equals(saved.getAction().get("type"));
            boolean dry = Boolean.TRUE.equals(saved.getDry());
            String icon = !saved.succeeded() ? "❌" : isNotify ? "🔔" : Boolean.TRUE.equals(saved.getSkipped()) ? "⏭️" : dry ? "🧪" : "✅";
            String tag = dry && !isNotify ? " (chạy thử)" : "";
            telegram.telegram(icon + " <b>" + saved.getSource() + "</b>" + tag + "\n" + saved.getName() + ": " + saved.getDetail());
        }
        return saved;
    }

    public static Map<String, Object> target(AdObject o) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", o.id);
        t.put("name", o.name);
        t.put("level", o.level);
        if (o.accountId != null) { t.put("accountId", o.accountId); t.put("accountName", o.accountName); }
        return t;
    }

    public static Map<String, Object> actionJson(Action a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", a.type());
        if (a.isBudget()) {
            m.put("mode", a.mode());
            m.put("value", a.value());
            if (a.max() != 0) m.put("max", a.max());
            if (a.min() != 0) m.put("min", a.min());
        }
        return m;
    }

    // ------------------------------------------------------------------ Bảo vệ ngân sách
    /** Ngân sách chỉ nằm ở 1 cấp: camp CBO giữ ngân sách (nhóm QC không có), camp ABO thì ngược lại */
    public static String noBudgetReason(AdObject o) {
        return "adset".equals(o.level)
                ? "Nhóm QC không có ngân sách riêng (chiến dịch dùng ngân sách chiến dịch - CBO), chỉnh ngân sách ở cấp chiến dịch"
                : "Chiến dịch không có ngân sách riêng (ngân sách đặt ở từng nhóm QC - ABO), hãy dùng rule cấp nhóm QC";
    }

    /** Ngân sách "gốc" hôm nay: giá trị trước lần đổi đầu tiên do rule (để tính giới hạn thay đổi cộng dồn) */
    private Double budgetBase(String objId) {
        return state.daily(clock.now().date(), "base:" + objId).map(m -> m.getNumValue()).orElse(null);
    }

    /** Kế hoạch cho một hành động (không gọi Facebook, không ghi trạng thái) */
    public Plan plan(AdObject obj, Action action, ActCtx ctx) {
        if (action.isNotify()) return Plan.notifyIt(action.message() != null && !action.message().isEmpty() ? action.message() : "Cảnh báo");
        if (action.type().equals("on") || action.type().equals("off")) {
            boolean want = action.type().equals("on");
            if (obj.isActive() == want) return Plan.noop(); // đã đúng trạng thái
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", want ? "ACTIVE" : "PAUSED");
            return Plan.doIt((want ? "Bật " : "Tắt ") + obj.unit(), after, 0, false);
        }
        if (obj.dailyBudget == null) return Plan.error(noBudgetReason(obj) + ".");
        AppSettings s = settings.get();
        boolean isRule = ctx != null && ctx.isRule();
        int capPct = s.getDailyChangeCapPct() > 0 ? s.getDailyChangeCapPct() : 30;
        if (isRule && s.isSkipLearning() && obj.learning)
            return Plan.skip("learning", "Đang trong giai đoạn học nên tạm không đổi ngân sách (tránh làm Facebook học lại từ đầu).");
        double cur = obj.dailyBudget;
        double next = "percent".equals(action.mode()) ? cur * (1 + action.value() / 100) : "add".equals(action.mode()) ? cur + action.value() : action.value();
        if (action.max() != 0) next = Math.min(next, action.max());
        if (action.min() != 0) next = Math.max(next, action.min());
        next = Math.round(next);
        if (!(next > 0)) return Plan.error("Ngân sách mới sẽ là " + Fmt.money(next) + " (không lớn hơn 0) nên không đổi.");
        boolean capped = false;
        double base = cur;
        if (isRule) { // giới hạn tổng thay đổi mỗi ngày (chỉ áp dụng cho rule; lịch là ý định rõ ràng của bạn)
            Double b = budgetBase(obj.id);
            base = b != null ? b : cur;
            double lo = Math.round(base * (1 - capPct / 100.0)), hi = Math.round(base * (1 + capPct / 100.0));
            double c = Math.min(hi, Math.max(lo, next));
            if (c != next) { capped = true; next = c; }
        }
        if (next == Math.round(cur)) {
            return capped ? Plan.skip("cap", "Đã đạt giới hạn thay đổi " + capPct + "% mỗi ngày (ngân sách gốc hôm nay " + Fmt.money(base) + ").") : Plan.noop();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dailyBudget", next);
        return Plan.doIt("Ngân sách " + Fmt.money(cur) + " → " + Fmt.money(next) + (capped ? " (chạm giới hạn " + capPct + "%/ngày)" : ""), after, next, capped);
    }

    /**
     * Thực thi 1 hành động lên 1 đối tượng. Trả về: noop | skip | error | ok | fail.
     * Chạy thử (dryRun trên dữ liệu thật): không gọi Facebook, chỉ ghi nhật ký.
     */
    public String act(AdObject obj, Action action, String source, ActCtx ctx) {
        AppSettings s = settings.get();
        boolean dry = s.isDry();
        boolean isNotify = action.isNotify();
        String mode = s.mode();
        Map<String, Object> before = FacebookService.snapshot(obj);
        Consumer<LogEntry> base = e -> {
            e.setKind(ctx.kind() != null ? ctx.kind() : "manual");
            e.setRefId(ctx.refId());
            e.setRefName(ctx.refName());
            if (ctx.condition() != null) e.setCondition(ctx.condition());
            e.setTarget(target(obj));
            e.setMode(mode);
            e.setBefore(before);
            e.setAction(actionJson(action));
            e.setSource(source);
            e.setName(obj.name);
        };
        Plan p = plan(obj, action, ctx);
        if (p.is("noop")) return "noop";
        if (p.is("skip")) { // ghi nhận việc bỏ qua, mỗi lý do 1 lần/ngày/camp để khỏi đầy nhật ký; không gửi Telegram
            String day = clock.now().date(), key = "skip:" + (ctx.refId() == null ? "" : ctx.refId()) + ":" + obj.id + ":" + p.code();
            if (state.hasDaily(day, key)) return "skip";
            state.putDaily(day, key, null);
            record(true, e -> { base.accept(e); e.setDetail("Bỏ qua: " + p.reason()); e.setOk(true); e.setDry(dry); e.setSkipped(true); });
            return "skip";
        }
        if (p.is("error")) {
            record(ctx.silent(), e -> { base.accept(e); e.setDetail(p.detail()); e.setOk(false); e.setDry(dry); e.setError(Map.of("message", p.detail())); });
            return "error";
        }
        try {
            if (!isNotify && !dry) {
                if (p.after().containsKey("status")) {
                    fb.setStatus(obj.id, "ACTIVE".equals(p.after().get("status")));
                    obj.status = obj.effective = (String) p.after().get("status");
                } else {
                    fb.setBudget(obj.id, p.next());
                    if (ctx.isRule()) {
                        String day = clock.now().date();
                        if (!state.hasDaily(day, "base:" + obj.id)) state.putDaily(day, "base:" + obj.id, obj.dailyBudget);
                    }
                    obj.dailyBudget = p.next();
                }
            }
            record(ctx.silent(), e -> { base.accept(e); e.setDetail(p.detail()); e.setOk(true); e.setDry(!isNotify && dry); e.setAfter(p.after()); });
            return "ok";
        } catch (RuntimeException ex) {
            record(ctx.silent(), e -> { base.accept(e); e.setDetail(ex.getMessage()); e.setOk(false); e.setDry(dry); e.setError(FbException.describe(ex)); });
            return "fail";
        }
    }
}
