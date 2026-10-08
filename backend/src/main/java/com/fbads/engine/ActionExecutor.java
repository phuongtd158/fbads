package com.fbads.engine;

import com.fbads.client.FbException;
import com.fbads.common.Fmt;
import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogKind;
import com.fbads.service.EngineState;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookActions;
import com.fbads.service.facebook.FacebookObjects;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lập kế hoạch (plan) và thực thi (act) một hành động lên camp/nhóm QC, tôn trọng chế độ chạy thử,
 * ghi nhật ký (thông báo do consumer của sự kiện log.created gửi, xem notify/Notifier). Bản Java của
 * plan()/act()/record() trong lib/engine.js.
 */
@Service
public class ActionExecutor {
    private final SettingsService settings;
    private final FacebookActions actions;
    private final LogService logs;
    private final EngineState state;
    private final EngineClock clock;

    public ActionExecutor(SettingsService settings, FacebookActions actions, LogService logs, EngineState state, EngineClock clock) {
        this.settings = settings;
        this.actions = actions;
        this.logs = logs;
        this.state = state;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ Nhật ký
    /** Ghi nhật ký; không silent thì sự kiện kèm yêu cầu báo Telegram. */
    public LogEntry record(LogEntry e, boolean silent) {
        return logs.add(e, !silent);
    }

    public static Map<String, Object> target(AdObject o) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", o.id);
        t.put("name", o.name);
        t.put("level", o.level.code());
        if (o.accountId != null) { t.put("accountId", o.accountId); t.put("accountName", o.accountName); }
        return t;
    }

    public static Map<String, Object> actionJson(Action a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", a.type().code());
        if (a.isBudget()) {
            m.put("mode", a.mode().code());
            m.put("value", a.value());
            if (a.max() != 0) m.put("max", a.max());
            if (a.min() != 0) m.put("min", a.min());
        }
        return m;
    }

    // ------------------------------------------------------------------ Bảo vệ ngân sách
    /** Ngân sách chỉ nằm ở 1 cấp: camp CBO giữ ngân sách (nhóm QC không có), camp ABO thì ngược lại */
    public static String noBudgetReason(AdObject o) {
        return o.level == AdLevel.ADSET
                ? "Nhóm QC không có ngân sách riêng (chiến dịch dùng ngân sách chiến dịch - CBO), chỉnh ngân sách ở cấp chiến dịch"
                : "Chiến dịch không có ngân sách riêng (ngân sách đặt ở từng nhóm QC - ABO), hãy dùng rule cấp nhóm QC";
    }

    /** Ngân sách "gốc" hôm nay: giá trị trước lần đổi đầu tiên do rule (để tính giới hạn thay đổi cộng dồn) */
    private Double budgetBase(String objId) {
        return state.daily(clock.now().date(), "base:" + objId).map(m -> m.getNumValue()).orElse(null);
    }

    /** Kế hoạch cho một hành động (không gọi Facebook, không ghi trạng thái) */
    public Plan plan(AdObject obj, Action action, ActCtx ctx) {
        if (action.isNotify())
            return Plan.notifyIt(action.message() != null && !action.message().isEmpty() ? action.message() : "Cảnh báo");
        if (action.isOnOff()) {
            boolean want = action.type() == ActionType.ON;
            if (obj.isActive() == want) return Plan.noop(); // đã đúng trạng thái
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", want ? "ACTIVE" : "PAUSED");
            return Plan.doIt((want ? "Bật " : "Tắt ") + obj.unit(), after, 0, false);
        }
        if (obj.dailyBudget == null) return Plan.error(noBudgetReason(obj) + ".");
        AppSettings s = settings.get();
        boolean isRule = ctx != null && ctx.isRule();
        int capPct = s.getDailyChangeCapPct() > 0 ? s.getDailyChangeCapPct() : 30;
        if (isRule && s.isSkipLearning() && obj.learning && !ctx.includeLearning())
            return Plan.skip("learning", "Đang trong giai đoạn học nên tạm không đổi ngân sách (tránh làm Facebook học lại từ đầu).");
        double cur = obj.dailyBudget;
        double next = switch (action.mode()) {
            case PERCENT -> cur * (1 + action.value() / 100);
            case ADD -> cur + action.value();
            case SET -> action.value();
        };
        if (action.max() != 0) next = Math.min(next, action.max());
        if (action.min() != 0) next = Math.max(next, action.min());
        next = Math.round(next);
        if (!(next > 0)) return Plan.error("Ngân sách mới sẽ là " + Fmt.money(next) + " (không lớn hơn 0) nên không đổi.");
        boolean capped = false;
        double base = cur;
        // giới hạn tổng thay đổi mỗi ngày (chỉ áp dụng cho rule; lịch là ý định rõ ràng của bạn; rule tăng theo bậc
        // không dùng vì đã bắt buộc có trần)
        if (isRule && !ctx.noCap()) {
            Double b = budgetBase(obj.id);
            base = b != null ? b : cur;
            double lo = Math.round(base * (1 - capPct / 100.0)), hi = Math.round(base * (1 + capPct / 100.0));
            double c = Math.min(hi, Math.max(lo, next));
            if (c != next) { capped = true; next = c; }
        }
        if (next == Math.round(cur)) {
            return capped ? Plan.skip("cap", "Đã đạt giới hạn thay đổi " + capPct + "% mỗi ngày (ngân sách gốc hôm nay "
                    + Fmt.money(base) + ").") : Plan.noop();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dailyBudget", next);
        return Plan.doIt("Ngân sách " + Fmt.money(cur) + " → " + Fmt.money(next)
                + (capped ? " (chạm giới hạn " + capPct + "%/ngày)" : ""), after, next, capped);
    }

    /**
     * Thực thi 1 hành động lên 1 đối tượng (kết quả: xem ActResult).
     * Chạy thử (dryRun trên dữ liệu thật): không gọi Facebook, chỉ ghi nhật ký.
     */
    public ActResult act(AdObject obj, Action action, String source, ActCtx ctx) {
        boolean dry = settings.get().isDry();
        Map<String, Object> before = FacebookObjects.snapshot(obj);
        Plan p = plan(obj, action, ctx);
        return switch (p.kind()) {
            case NOOP -> ActResult.NOOP;
            case SKIP -> recordSkip(obj, action, source, ctx, p, before, dry);
            case ERROR -> recordError(obj, action, source, ctx, p, before, dry);
            case DO -> carryOut(obj, action, source, ctx, p, before, dry);
        };
    }

    /** Ghi nhận việc bỏ qua, mỗi lý do 1 lần/ngày/camp để khỏi đầy nhật ký; không gửi Telegram */
    private ActResult recordSkip(AdObject obj, Action action, String source, ActCtx ctx, Plan p, Map<String, Object> before,
            boolean dry) {
        String day = clock.now().date(), key = "skip:" + (ctx.refId() == null ? "" : ctx.refId()) + ":" + obj.id + ":" + p.code();
        if (state.hasDaily(day, key)) return ActResult.SKIP;
        state.putDaily(day, key, null);
        LogEntry e = entry(obj, action, source, ctx, before);
        e.setDetail("Bỏ qua: " + p.reason());
        e.setOk(true);
        e.setDry(dry);
        e.setSkipped(true);
        record(e, true);
        return ActResult.SKIP;
    }

    /** Kế hoạch không làm được (vd ngân sách mới ≤ 0): chỉ ghi lỗi, không gọi Facebook */
    private ActResult recordError(AdObject obj, Action action, String source, ActCtx ctx, Plan p, Map<String, Object> before,
            boolean dry) {
        LogEntry e = entry(obj, action, source, ctx, before);
        e.setDetail(p.detail());
        e.setOk(false);
        e.setDry(dry);
        e.setError(Map.of("message", p.detail()));
        record(e, ctx.silent());
        return ActResult.ERROR;
    }

    /** Làm theo kế hoạch (gọi Facebook, trừ khi chạy thử hoặc chỉ thông báo) rồi ghi nhật ký */
    private ActResult carryOut(AdObject obj, Action action, String source, ActCtx ctx, Plan p, Map<String, Object> before,
            boolean dry) {
        try {
            if (!action.isNotify() && !dry) {
                if (p.after().containsKey("status")) {
                    actions.setStatus(obj.id, "ACTIVE".equals(p.after().get("status")));
                    obj.status = obj.effective = (String) p.after().get("status");
                } else {
                    actions.setBudget(obj.id, p.next());
                    if (ctx.isRule()) {
                        String day = clock.now().date();
                        if (!state.hasDaily(day, "base:" + obj.id)) state.putDaily(day, "base:" + obj.id, obj.dailyBudget);
                    }
                    obj.dailyBudget = p.next();
                }
            }
            LogEntry e = entry(obj, action, source, ctx, before);
            e.setDetail(p.detail());
            e.setOk(true);
            e.setDry(!action.isNotify() && dry);
            e.setAfter(p.after());
            record(e, ctx.silent());
            return ActResult.OK;
        } catch (RuntimeException ex) {
            LogEntry e = entry(obj, action, source, ctx, before);
            e.setDetail(ex.getMessage());
            e.setOk(false);
            e.setDry(dry);
            e.setError(FbException.describe(ex));
            record(e, ctx.silent());
            return ActResult.FAIL;
        }
    }

    /** Phần chung của mọi dòng nhật ký do act() ghi: ai làm, làm gì, lên camp nào, trạng thái trước đó */
    private LogEntry entry(AdObject obj, Action action, String source, ActCtx ctx, Map<String, Object> before) {
        LogEntry e = LogEntry.of(ctx.kind() != null ? ctx.kind() : LogKind.MANUAL, source, obj.name);
        e.setRefId(ctx.refId());
        e.setRefName(ctx.refName());
        if (ctx.condition() != null) e.setCondition(ctx.condition());
        e.setTarget(target(obj));
        e.setMode(settings.get().mode());
        e.setBefore(before);
        e.setAction(actionJson(action));
        return e;
    }
}
