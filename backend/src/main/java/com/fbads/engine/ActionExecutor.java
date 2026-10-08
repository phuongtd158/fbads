package com.fbads.engine;

import com.fbads.ads.AdObject;
import com.fbads.common.Fmt;
import com.fbads.facebook.FacebookActions;
import com.fbads.log.LogAction;
import com.fbads.log.LogChange;
import com.fbads.log.LogEntry;
import com.fbads.log.LogError;
import com.fbads.log.LogKind;
import com.fbads.log.LogService;
import com.fbads.log.LogSnapshot;
import com.fbads.log.LogTarget;
import com.fbads.service.EngineState;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

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

    // ------------------------------------------------------------------ Bảo vệ ngân sách
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
            return Plan.doIt((want ? "Bật " : "Tắt ") + obj.unit(), LogChange.status(want), 0, false);
        }
        if (obj.dailyBudget() == null) return Plan.error(obj.noBudgetReason() + ".");
        AppSettings s = settings.get();
        boolean isRule = ctx != null && ctx.isRule();
        int capPct = s.getDailyChangeCapPct() > 0 ? s.getDailyChangeCapPct() : 30;
        if (isRule && s.isSkipLearning() && obj.learning() && !ctx.includeLearning())
            return Plan.skip("learning", "Đang trong giai đoạn học nên tạm không đổi ngân sách (tránh làm Facebook học lại từ đầu).");
        double cur = obj.dailyBudget();
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
            Double b = budgetBase(obj.id());
            base = b != null ? b : cur;
            double lo = Math.round(base * (1 - capPct / 100.0)), hi = Math.round(base * (1 + capPct / 100.0));
            double c = Math.min(hi, Math.max(lo, next));
            if (c != next) { capped = true; next = c; }
        }
        if (next == Math.round(cur)) {
            return capped ? Plan.skip("cap", "Đã đạt giới hạn thay đổi " + capPct + "% mỗi ngày (ngân sách gốc hôm nay "
                    + Fmt.money(base) + ").") : Plan.noop();
        }
        return Plan.doIt("Ngân sách " + Fmt.money(cur) + " → " + Fmt.money(next)
                + (capped ? " (chạm giới hạn " + capPct + "%/ngày)" : ""), LogChange.budget(next), next, capped);
    }

    /**
     * Thực thi 1 hành động lên 1 đối tượng (kết quả: xem ActResult).
     * Chạy thử (dryRun trên dữ liệu thật): không gọi Facebook, chỉ ghi nhật ký.
     */
    public ActResult act(AdObject obj, Action action, String source, ActCtx ctx) {
        boolean dry = settings.get().isDry();
        LogSnapshot before = LogSnapshot.of(obj);
        Plan p = plan(obj, action, ctx);
        return switch (p.kind()) {
            case NOOP -> ActResult.NOOP;
            case SKIP -> recordSkip(obj, action, source, ctx, p, before, dry);
            case ERROR -> recordError(obj, action, source, ctx, p, before, dry);
            case DO -> carryOut(obj, action, source, ctx, p, before, dry);
        };
    }

    /** Ghi nhận việc bỏ qua, mỗi lý do 1 lần/ngày/camp để khỏi đầy nhật ký; không gửi Telegram */
    private ActResult recordSkip(AdObject obj, Action action, String source, ActCtx ctx, Plan p, LogSnapshot before,
            boolean dry) {
        String day = clock.now().date(), key = "skip:" + (ctx.refId() == null ? "" : ctx.refId()) + ":" + obj.id() + ":" + p.code();
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
    private ActResult recordError(AdObject obj, Action action, String source, ActCtx ctx, Plan p, LogSnapshot before,
            boolean dry) {
        LogEntry e = entry(obj, action, source, ctx, before);
        e.setDetail(p.detail());
        e.setOk(false);
        e.setDry(dry);
        e.setError(LogError.of(p.detail()));
        record(e, ctx.silent());
        return ActResult.ERROR;
    }

    /** Làm theo kế hoạch (gọi Facebook, trừ khi chạy thử hoặc chỉ thông báo) rồi ghi nhật ký */
    private ActResult carryOut(AdObject obj, Action action, String source, ActCtx ctx, Plan p, LogSnapshot before,
            boolean dry) {
        try {
            if (!action.isNotify() && !dry) {
                if (p.after().changesStatus()) {
                    actions.setStatus(obj.id(), p.after().turnsOn());
                    obj.applyStatus(p.after().turnsOn());
                } else {
                    actions.setBudget(obj.id(), p.next());
                    if (ctx.isRule()) {
                        String day = clock.now().date();
                        if (!state.hasDaily(day, "base:" + obj.id())) state.putDaily(day, "base:" + obj.id(), obj.dailyBudget());
                    }
                    obj.applyBudget(p.next());
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
            e.setError(LogError.of(ex));
            record(e, ctx.silent());
            return ActResult.FAIL;
        }
    }

    /** Phần chung của mọi dòng nhật ký do act() ghi: ai làm, làm gì, lên camp nào, trạng thái trước đó */
    private LogEntry entry(AdObject obj, Action action, String source, ActCtx ctx, LogSnapshot before) {
        LogEntry e = LogEntry.of(ctx.kind() != null ? ctx.kind() : LogKind.MANUAL, source, obj.name());
        e.setRefId(ctx.refId());
        e.setRefName(ctx.refName());
        if (ctx.condition() != null) e.setCondition(ctx.condition());
        e.setTarget(LogTarget.of(obj));
        e.setMode(settings.get().mode());
        e.setBefore(before);
        e.setAction(LogAction.of(action));
        return e;
    }
}
