package com.fbads.rule;

import com.fbads.ads.AdLevel;
import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;
import com.fbads.common.Fmt;
import com.fbads.engine.ActCtx;
import com.fbads.engine.Action;
import com.fbads.engine.ActionExecutor;
import com.fbads.engine.BudgetMode;
import com.fbads.engine.Delivery;
import com.fbads.engine.EngineClock;
import com.fbads.engine.Labels;
import com.fbads.engine.Plan;
import com.fbads.log.LogKind;
import com.fbads.service.EngineState;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Đánh giá một rule trên danh sách camp/nhóm QC (không thay đổi gì; dùng cho cả chạy thật lẫn Xem trước).
 * Bản Java của evaluateRule() + metricValue() trong lib/engine.js.
 */
@Component
public class RuleEvaluator {
    /**
     * Kết quả xét 1 điều kiện cho 1 camp. tierMetric/tierCount/tier: ngưỡng chi tiêu nâng theo số kết quả (bậc đã đạt, null = ngưỡng gốc);
     * ladderStep/ladderNeed: rule tăng theo bậc (bậc đạt được, số kết quả cần).
     */
    public record CondEval(String metric, String op, String vs, Double factor, String compareRange,
            double actual, Double threshold, boolean unknown, boolean hit, String tierMetric, Double tierCount,
            Condition.Tier tier, Integer ladderStep, Integer ladderNeed) {
        CondEval(String metric, String op, String vs, Double factor, String compareRange, double actual, Double threshold,
                boolean unknown, boolean hit) {
            this(metric, op, vs, factor, compareRange, actual, threshold, unknown, hit, null, null, null, null, null);
        }
    }

    /** Rule tăng theo bậc: bậc vừa đạt (0 = bậc 1), số kết quả đang có, số kết quả cần, loại kết quả */
    public record Ladder(int step, double count, double need, String metric) {}

    /**
     * Kết quả xét rule cho 1 camp. Chỉ RuleEvaluator điền vào (qua các bước có tên: skip, measured, reachedStep, planned);
     * nơi khác chỉ đọc.
     */
    public static final class Decision {
        private final AdObject obj;
        private final RuleRange range;
        private final Metrics metrics;
        private Double value;
        private boolean hit, eligible;
        private DecisionStatus status = DecisionStatus.NOMATCH;
        private String code = "", reason = "";
        private Plan plan;
        private List<CondEval> conds = List.of();
        private Action action;
        private Ladder ladder;
        private ActCtx planCtx;

        Decision(AdObject obj, RuleRange range, Metrics metrics) {
            this.obj = obj;
            this.range = range;
            this.metrics = metrics;
        }

        /** Bỏ qua camp này (đang tắt, chưa đủ chi tiêu, đang nghỉ…): code để máy đọc, reason để người đọc */
        void skip(String code, String reason) {
            this.status = DecisionStatus.SKIP;
            this.code = code;
            this.reason = reason;
        }

        /** Đã đo các điều kiện: value = số liệu của điều kiện đầu, hit = rule khớp */
        void measured(List<CondEval> conds, Double value, boolean hit) {
            this.conds = conds;
            this.value = value;
            this.hit = hit;
        }

        /** Chưa khớp, kèm lý do cho người đọc (vd rule tăng theo bậc chưa đạt bậc 1) */
        void notYet(String reason) { this.reason = reason; }

        /** Rule tăng theo bậc: đã đạt bậc này */
        void reachedStep(Ladder ladder) {
            this.hit = true;
            this.ladder = ladder;
        }

        /** Đủ điều kiện ra tay: lưu hành động, ngữ cảnh và kế hoạch; noopReason = lý do khi kế hoạch không cần làm gì */
        void planned(Action action, ActCtx ctx, Plan plan, String noopReason) {
            this.eligible = true;
            this.action = action;
            this.planCtx = ctx;
            this.plan = plan;
            this.status = DecisionStatus.of(plan.kind());
            switch (plan.kind()) {
                case SKIP -> { code = plan.code(); reason = plan.reason(); }
                case NOOP -> reason = noopReason;
                case ERROR -> reason = plan.reason();
                case DO -> { }
            }
        }

        public AdObject obj() { return obj; }

        public RuleRange range() { return range; }

        public Metrics metrics() { return metrics; }

        public Double value() { return value; }

        public boolean hit() { return hit; }

        /** Qua hết các bước kiểm tra (không bị bỏ qua) nên đã có kế hoạch */
        public boolean eligible() { return eligible; }

        public DecisionStatus status() { return status; }

        public String code() { return code; }

        public String reason() { return reason; }

        public Plan plan() { return plan; }

        public List<CondEval> conds() { return conds; }

        public Action action() { return action; }

        /** Rule tăng theo bậc: bậc đạt được (null = không phải rule tăng theo bậc / chưa đạt) */
        public Ladder ladder() { return ladder; }

        /** Ngữ cảnh thực thi (rule tăng theo bậc: không giới hạn %/ngày, có tăng nhóm đang học không) */
        public ActCtx planCtx() { return planCtx; }
    }

    private final ActionExecutor executor;
    private final EngineState state;
    private final SettingsService settings;
    private final EngineClock clock;

    public RuleEvaluator(ActionExecutor executor, EngineState state, SettingsService settings, EngineClock clock) {
        this.executor = executor;
        this.state = state;
        this.settings = settings;
        this.clock = clock;
    }

    /** Đang chạy thật: bật và (nếu có thông tin phân phối) đang phân phối. Luôn xét effective: mục vừa bị rule trước tắt
     *  trong cùng lượt đã được cập nhật effective = PAUSED. */
    private static boolean isRunning(AdObject o, Delivery.View running) {
        return o.isActive() && (running == null || running.running(o));
    }

    private static double costPer(double spend, double n) { return n > 0 ? spend / n : spend > 0 ? Double.POSITIVE_INFINITY : 0; }

    /** Giá trị của một số liệu để so với ngưỡng. Chi phí khi chưa có mẫu số mà đã chi tiêu = ∞ ("đắt vô hạn"), không phải 0. */
    public static double metricValue(Metrics m, String metric) {
        double imp = m.impressions(), clicks = m.clicks();
        return switch (metric) {
            case "cpa" -> m.results() > 0 ? m.cpa() : m.spend() > 0 ? Double.POSITIVE_INFINITY : 0;
            case "roas" -> m.roas() == null ? 0 : m.roas();
            case "results" -> m.results();
            case "ctr" -> imp > 0 ? clicks * 100 / imp : 0;
            case "cpc" -> clicks > 0 ? m.spend() / clicks : m.spend() > 0 ? Double.POSITIVE_INFINITY : 0;
            case "cpm" -> imp > 0 ? m.spend() * 1000 / imp : 0;
            case "messages" -> m.conversations();
            case "leads" -> m.leads();
            case "costPerMessage" -> costPer(m.spend(), m.conversations());
            case "costPerLead" -> costPer(m.spend(), m.leads());
            case "frequency" -> m.reach() > 0 ? imp / m.reach() : 0;
            default -> m.spend();
        };
    }

    /**
     * Ngưỡng của 1 điều kiện cho 1 camp: số cụ thể; mục tiêu của tài khoản × factor%; hoặc chính số liệu đó ở khoảng khác × factor%.
     * null = chưa quyết định được (tài khoản chưa đặt mục tiêu / khoảng so sánh chưa có số liệu).
     */
    private Double thresholdOf(Condition c, AdObject obj, Map<String, Map<String, Metrics>> byRange) {
        if (c.vsRange()) {
            Map<String, Metrics> m = byRange.get(c.compareRange());
            Metrics bm = m == null ? null : m.get(obj.id());
            return bm != null && bm.spend() > 0 ? RuleValidator.compareThreshold(c, metricValue(bm, c.metric())) : null;
        }
        if (!c.vsTarget()) return c.value() == null ? 0 : c.value();
        double t = settings.get().target(obj.accountId(), "spend".equals(c.metric()) ? "cpa" : c.metric()); // chi tiêu so với CPA mục tiêu
        double f = c.factor() == null || c.factor() == 0 ? 100 : c.factor();
        return t > 0 ? t * f / 100 : null;
    }

    private static String fmtTh(CondEval c) {
        return "roas".equals(c.metric()) || "frequency".equals(c.metric()) ? Fmt.fixed2(c.threshold())
                : "ctr".equals(c.metric()) ? Fmt.fixed2(c.threshold()) + "%" : Fmt.money(c.threshold());
    }

    /** Ngưỡng chi tiêu nâng theo số kết quả: bậc cao nhất đã đạt, không đạt bậc nào thì ngưỡng gốc (tier = null) */
    public record TierPick(double value, Condition.Tier tier) {}

    public static TierPick spendTierOf(Condition c, double count) {
        Condition.Tier pick = null;
        for (Condition.Tier t : c.tiers()) if (count >= t.count() && (pick == null || t.count() > pick.count())) pick = t;
        return pick != null ? new TierPick(pick.value(), pick) : new TierPick(c.value() == null ? 0 : c.value(), null);
    }

    private static String vsText(CondEval c) {
        if ("target".equals(c.vs())) return " (" + Fmt.num(c.factor()) + "% " + ("spend".equals(c.metric()) ? "CPA " : "") + "mục tiêu)";
        if ("range".equals(c.vs()))
            return " (" + Fmt.num(c.factor()) + "% " + Labels.metric(c.metric()) + " " + Labels.range(c.compareRange())
                    + (RuleValidator.TOTAL_METRICS.contains(c.metric())
                            && RuleRange.daysOf(c.compareRange()) > 1 ? ", trung bình mỗi ngày" : "") + ")";
        return "";
    }

    private static String tierText(CondEval c) {
        if (c.tierMetric() == null) return "";
        return " (đã có " + Fmt.num(c.tierCount()) + " " + Labels.metric(c.tierMetric())
                + (c.tier() != null ? ", đạt bậc từ " + Fmt.num(c.tier().count()) : "") + ")";
    }

    static String condSentence(CondEval c, RuleRange range) {
        return Labels.metric(c.metric()) + " " + Labels.range(range) + " = " + Labels.show(c.actual(), c.metric()) + " "
                + (">".equals(c.op()) ? "lớn hơn" : "nhỏ hơn") + " ngưỡng " + fmtTh(c) + vsText(c) + tierText(c);
    }

    /** Tên loại kết quả viết thường: "kết quả", "lead", "tin nhắn" */
    static String unitWord(String m) { return Labels.metric(m).toLowerCase(); }

    public static Action actionOf(Rule rule) {
        if (rule.getAction() == RuleAction.PAUSE) return Action.off();
        if (rule.getAction() == RuleAction.NOTIFY) return Action.notifyOnly();
        int sign = rule.getAction() == RuleAction.INCREASE ? 1 : -1;
        if ("amount".equals(rule.getBudgetMode()))
            return Action.budget(BudgetMode.ADD, sign * rule.getAmount(), rule.getMaxBudget(), rule.getMinBudget());
        return Action.budget(BudgetMode.PERCENT, sign * rule.getPct(), rule.getMaxBudget(), rule.getMinBudget());
    }

    /**
     * Xét rule trên danh sách camp. byRange: số liệu theo khoảng (mã khoảng → id camp → số liệu), gồm khoảng của rule và
     * các khoảng so sánh; running: trạng thái phân phối (null = chỉ xem trạng thái bật/tắt).
     */
    public List<Decision> evaluate(Rule rule, List<AdObject> objs, Map<String, Map<String, Metrics>> byRange, Delivery.View running) {
        EngineClock.Now now = clock.now();
        long nowMs = clock.millis();
        RuleRange range = rule.getRange() == null ? RuleRange.TODAY : rule.getRange();
        Map<String, Metrics> map = byRange.get(range.code());
        if (map == null) map = Map.of();
        AdLevel level = rule.getLevel() == null ? AdLevel.CAMPAIGN : rule.getLevel();
        boolean hasWindow = !rule.getFrom().isEmpty() && !rule.getTo().isEmpty();
        boolean outside = hasWindow && (now.minutes() < EngineClock.toMin(rule.getFrom())
                || now.minutes() > EngineClock.toMin(rule.getTo()));
        List<String> accs = rule.getAccountIds() == null ? List.of() : rule.getAccountIds();
        // luôn xét cả effective: mục vừa bị rule trước tắt trong cùng lượt đã được cập nhật effective = PAUSED
        List<AdObject> list = rule.isAllActive()
                ? objs.stream().filter(o -> o.level() == level && isRunning(o, running)
                        && (accs.isEmpty() || accs.contains(o.accountId()))).toList()
                : objs.stream().filter(o -> rule.getTargets() != null && rule.getTargets().contains(o.id())).toList();
        List<Condition> conditions = rule.conditionList();
        boolean any = rule.getMatch() == MatchMode.ANY;
        Action action = actionOf(rule);

        List<Decision> out = new ArrayList<>();
        for (AdObject obj : list) {
            Decision d = new Decision(obj, range, map.getOrDefault(obj.id(), Metrics.EMPTY));
            out.add(d);
            if (!isRunning(obj, running)) {
                String why = running != null ? running.label(obj) : "";
                d.skip("inactive", ("camp".equals(obj.unit()) ? "Camp" : "Nhóm QC") + " không đang chạy"
                        + (why.isEmpty() ? "" : " (" + why + ")"));
                continue;
            }
            if (action.isBudget() && obj.dailyBudget() == null) { d.skip("nobudget", obj.noBudgetReason()); continue; }
            if (outside) { d.skip("window", "Ngoài khung giờ của rule (" + rule.getFrom() + "–" + rule.getTo() + ")"); continue; }
            if (d.metrics().spend() < rule.getMinSpend()) {
                d.skip("minspend", "Chưa đủ chi tiêu tối thiểu (" + Fmt.money(d.metrics().spend()) + " < "
                        + Fmt.money(rule.getMinSpend()) + ")");
                continue;
            }
            if (rule.isLadder()) { evaluateLadder(rule, obj, d, now, nowMs); continue; }
            // Từng điều kiện rồi gộp bằng VÀ (mặc định) hoặc HOẶC. So với mục tiêu mà tài khoản chưa đặt mục tiêu → "chưa biết".
            List<CondEval> conds = new ArrayList<>();
            for (Condition c : conditions) {
                double actual = metricValue(d.metrics(), c.metric());
                // Ngưỡng nâng theo số kết quả: đếm kết quả trong cùng khoảng của rule rồi chọn bậc
                Double tierCount = c.hasTiers() ? metricValue(d.metrics(), c.tierMetric()) : null;
                TierPick tp = tierCount != null ? spendTierOf(c, tierCount) : null;
                Double th = tp != null ? Double.valueOf(tp.value()) : thresholdOf(c, obj, byRange);
                boolean unknown = th == null;
                boolean hit = !unknown && (">".equals(c.op()) ? actual > th : actual < th);
                conds.add(new CondEval(c.metric(), c.op(), c.vs(), c.factor(), c.compareRange(), actual, th, unknown, hit,
                        tp != null ? c.tierMetric() : null, tierCount, tp != null ? tp.tier() : null, null, null));
            }
            List<CondEval> known = conds.stream().filter(c -> !c.unknown()).toList();
            boolean someUnknown = known.size() < conds.size();
            boolean hit = any ? known.stream().anyMatch(CondEval::hit) : !someUnknown && known.stream().allMatch(CondEval::hit);
            d.measured(conds, conds.isEmpty() ? null : conds.getFirst().actual(), hit);
            if (!hit) {
                // Chưa quyết định được vì thiếu mục tiêu → báo lý do thay vì im lặng
                boolean undecided = someUnknown && (any || known.stream().allMatch(CondEval::hit));
                if (undecided) {
                    LinkedHashSet<String> need = new LinkedHashSet<>();
                    for (CondEval c : conds) if (c.unknown() && "target".equals(c.vs())) need.add(Labels.metric(c.metric()));
                    if (!need.isEmpty()) {
                        String acc = obj.accountName() != null ? obj.accountName() : obj.accountId() != null ? obj.accountId() : "";
                        d.skip("notarget", "Tài khoản " + acc + " chưa đặt mục tiêu " + String.join(", ", need) + " (Cài đặt → Mục tiêu)");
                    } else {
                        LinkedHashSet<String> what = new LinkedHashSet<>();
                        for (CondEval c : conds)
                            if (c.unknown()) what.add(Labels.metric(c.metric()) + " " + Labels.range(c.compareRange()));
                        d.skip("nobaseline", "Chưa có số liệu " + String.join(", ", what) + " để so sánh (" + obj.unit()
                                + " chưa chi tiêu trong khoảng đó)");
                    }
                }
                continue;
            }
            RuleMark mark = state.mark(rule.getId() == null ? "" : rule.getId(), obj.id());
            long holdLeft = mark.holdUntil() - nowMs;
            if (holdLeft > 0) {
                d.skip("hold", "Tạm hoãn sau khi bạn hoàn tác (còn " + (long) Math.ceil(holdLeft / 3600e3) + " giờ)");
                continue;
            }
            long left = mark.lastRun() + (long) (rule.getCooldownHours() * 3600e3) - nowMs;
            if (left > 0) {
                d.skip("cooldown", "Đang nghỉ giữa hai lần (còn khoảng " + (long) Math.ceil(left / 3600e3) + " giờ)");
                continue;
            }
            Action act = action.isNotify()
                    ? action.withMessage(String.join(any ? " HOẶC " : " VÀ ",
                            conds.stream().filter(CondEval::hit).map(c -> condSentence(c, range)).toList()))
                    : action;
            ActCtx ctx = ActCtx.of(LogKind.RULE, rule.getId(), rule.getName());
            d.planned(act, ctx, executor.plan(obj, act, ctx), "Không có gì để thay đổi (đã ở trạng thái/ngân sách mong muốn)");
        }
        return out;
    }

    /**
     * Rule tăng theo bậc kết quả: bậc cao nhất đã đạt mà hôm nay chưa chạy thì tăng theo bậc đó (nhảy nhiều bậc chỉ chạy bậc cao nhất);
     * bậc cuối có everyHours thì lặp lại sau mỗi chừng ấy giờ. Bậc đã chạy lưu ở RuleMark (ngày, bậc, lúc chạy), sang ngày mới tính lại.
     */
    private void evaluateLadder(Rule rule, AdObject obj, Decision d, EngineClock.Now now, long nowMs) {
        List<Rule.Step> steps = rule.getSteps() == null ? List.of() : rule.getSteps();
        String m = rule.getLadderMetric() == null || rule.getLadderMetric().isEmpty() ? "results" : rule.getLadderMetric();
        double count = metricValue(d.metrics(), m);
        int h = -1;
        for (int i = 0; i < steps.size(); i++) if (count >= steps.get(i).count()) h = i;
        double refCount = steps.isEmpty() ? 1 : steps.get(Math.max(h, 0)).count();
        d.measured(List.of(new CondEval(m, ">", null, null, null, count, refCount - 1, false, h >= 0, null, null, null, h + 1,
                (int) refCount)), count, false);
        if (h < 0) { d.notYet("Chưa có " + Fmt.num(refCount) + " " + unitWord(m) + " (đang có " + Fmt.num(count) + ")"); return; }
        d.reachedStep(new Ladder(h, count, steps.get(h).count(), m));
        RuleMark mark = state.mark(rule.getId() == null ? "" : rule.getId(), obj.id());
        int done = mark.ladderStep(now.date());
        if (h <= done) {
            double every = h == steps.size() - 1 && steps.get(h).everyHours() != null ? steps.get(h).everyHours() : 0;
            if (every <= 0) {
                Rule.Step next = h + 1 < steps.size() ? steps.get(h + 1) : null;
                d.skip("ladderdone", next != null ? "Hôm nay đã tăng bậc " + (h + 1) + ", chờ có " + Fmt.num(next.count()) + " "
                        + unitWord(m) + " để lên bậc " + (h + 2)
                        : "Hôm nay đã tăng bậc cuối (bậc " + (h + 1) + ")");
                return;
            }
            long left = mark.ladderAt(now.date()) + (long) (every * 3600e3) - nowMs;
            if (left > 0) {
                d.skip("ladderwait", "Đã tăng bậc " + (h + 1) + ", lần tăng tiếp theo sau khoảng "
                        + (long) Math.ceil(left / 60e3) + " phút");
                return;
            }
        }
        long holdLeft = mark.holdUntil() - nowMs;
        if (holdLeft > 0) { d.skip("hold", "Tạm hoãn sau khi bạn hoàn tác (còn " + (long) Math.ceil(holdLeft / 3600e3) + " giờ)"); return; }
        Rule.Step t = steps.get(h);
        Action act = Action.budget("amount".equals(t.mode()) ? BudgetMode.ADD : BudgetMode.PERCENT, t.value(), rule.getMaxBudget(), 0);
        ActCtx ctx = ActCtx.of(LogKind.RULE, rule.getId(), rule.getName()).ladder(!Boolean.FALSE.equals(rule.getIncludeLearning()));
        d.planned(act, ctx, executor.plan(obj, act, ctx), "Đã chạm trần ngân sách " + Fmt.money(rule.getMaxBudget()));
    }
}
