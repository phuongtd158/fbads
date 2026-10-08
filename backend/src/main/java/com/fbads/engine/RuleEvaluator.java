package com.fbads.engine;

import com.fbads.dto.AdLevel;
import com.fbads.entity.LogKind;
import com.fbads.entity.MatchMode;
import com.fbads.entity.RuleAction;
import com.fbads.entity.RuleRange;
import com.fbads.validation.RuleValidator;

import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.dto.Condition;
import com.fbads.dto.Metrics;
import com.fbads.entity.Rule;
import com.fbads.entity.RuleMark;
import com.fbads.service.EngineState;
import com.fbads.service.SettingsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

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

    /** Kết quả xét rule cho 1 camp */
    public static final class Decision {
        public AdObject obj;
        public RuleRange range;
        public Metrics metrics;
        public Double value;
        public boolean hit, eligible;
        public DecisionStatus status = DecisionStatus.NOMATCH;
        public String code = "", reason = "";
        public Plan plan;
        public List<CondEval> conds = List.of();
        public Action action;
        /** Rule tăng theo bậc: bậc đạt được (null = không phải rule tăng theo bậc / chưa đạt) */
        public Ladder ladder;
        /** Ngữ cảnh thực thi riêng (rule tăng theo bậc: không giới hạn %/ngày, có tăng nhóm đang học không) */
        public ActCtx planCtx;

        Decision skip(String code, String reason) { this.status = DecisionStatus.SKIP; this.code = code; this.reason = reason; return this; }
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
    private Double thresholdOf(Condition c, AdObject obj, Function<String, Map<String, Metrics>> mapFor) {
        if (c.vsRange()) {
            Map<String, Metrics> m = mapFor.apply(c.compareRange());
            Metrics bm = m == null ? null : m.get(obj.id);
            return bm != null && bm.spend() > 0 ? RuleValidator.compareThreshold(c, metricValue(bm, c.metric())) : null;
        }
        if (!c.vsTarget()) return c.value() == null ? 0 : c.value();
        double t = settings.get().target(obj.accountId, "spend".equals(c.metric()) ? "cpa" : c.metric()); // chi tiêu so với CPA mục tiêu
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

    public List<Decision> evaluate(Rule rule, List<AdObject> objs, Function<String, Map<String, Metrics>> mapFor, Delivery.View running) {
        EngineClock.Now now = clock.now();
        long nowMs = clock.millis();
        RuleRange range = rule.getRange() == null ? RuleRange.TODAY : rule.getRange();
        Map<String, Metrics> map = mapFor.apply(range.code());
        if (map == null) map = Map.of();
        AdLevel level = rule.getLevel() == null ? AdLevel.CAMPAIGN : rule.getLevel();
        boolean hasWindow = !rule.getFrom().isEmpty() && !rule.getTo().isEmpty();
        boolean outside = hasWindow && (now.minutes() < EngineClock.toMin(rule.getFrom())
                || now.minutes() > EngineClock.toMin(rule.getTo()));
        List<String> accs = rule.getAccountIds() == null ? List.of() : rule.getAccountIds();
        // luôn xét cả effective: mục vừa bị rule trước tắt trong cùng lượt đã được cập nhật effective = PAUSED
        Predicate<AdObject> isRunning = o -> o.isActive() && (running == null || running.running(o));
        List<AdObject> list = rule.isAllActive()
                ? objs.stream().filter(o -> o.level == level && isRunning.test(o)
                        && (accs.isEmpty() || accs.contains(o.accountId))).toList()
                : objs.stream().filter(o -> rule.getTargets() != null && rule.getTargets().contains(o.id)).toList();
        List<Condition> conditions = rule.conditionList();
        boolean any = rule.getMatch() == MatchMode.ANY;
        Action action = actionOf(rule);

        List<Decision> out = new ArrayList<>();
        for (AdObject obj : list) {
            Decision d = new Decision();
            d.obj = obj;
            d.range = range;
            d.metrics = map.getOrDefault(obj.id, Metrics.EMPTY);
            out.add(d);
            if (!isRunning.test(obj)) {
                String why = running != null ? running.label(obj) : "";
                d.skip("inactive", ("camp".equals(obj.unit()) ? "Camp" : "Nhóm QC") + " không đang chạy"
                        + (why.isEmpty() ? "" : " (" + why + ")"));
                continue;
            }
            if (action.isBudget() && obj.dailyBudget == null) { d.skip("nobudget", ActionExecutor.noBudgetReason(obj)); continue; }
            if (outside) { d.skip("window", "Ngoài khung giờ của rule (" + rule.getFrom() + "–" + rule.getTo() + ")"); continue; }
            if (d.metrics.spend() < rule.getMinSpend()) {
                d.skip("minspend", "Chưa đủ chi tiêu tối thiểu (" + Fmt.money(d.metrics.spend()) + " < "
                        + Fmt.money(rule.getMinSpend()) + ")");
                continue;
            }
            if (rule.isLadder()) { evaluateLadder(rule, obj, d, now, nowMs); continue; }
            // Từng điều kiện rồi gộp bằng VÀ (mặc định) hoặc HOẶC. So với mục tiêu mà tài khoản chưa đặt mục tiêu → "chưa biết".
            List<CondEval> conds = new ArrayList<>();
            for (Condition c : conditions) {
                double actual = metricValue(d.metrics, c.metric());
                // Ngưỡng nâng theo số kết quả: đếm kết quả trong cùng khoảng của rule rồi chọn bậc
                Double tierCount = c.hasTiers() ? metricValue(d.metrics, c.tierMetric()) : null;
                TierPick tp = tierCount != null ? spendTierOf(c, tierCount) : null;
                Double th = tp != null ? Double.valueOf(tp.value()) : thresholdOf(c, obj, mapFor);
                boolean unknown = th == null;
                boolean hit = !unknown && (">".equals(c.op()) ? actual > th : actual < th);
                conds.add(new CondEval(c.metric(), c.op(), c.vs(), c.factor(), c.compareRange(), actual, th, unknown, hit,
                        tp != null ? c.tierMetric() : null, tierCount, tp != null ? tp.tier() : null, null, null));
            }
            d.conds = conds;
            d.value = conds.isEmpty() ? null : conds.getFirst().actual();
            List<CondEval> known = conds.stream().filter(c -> !c.unknown()).toList();
            boolean someUnknown = known.size() < conds.size();
            d.hit = any ? known.stream().anyMatch(CondEval::hit) : !someUnknown && known.stream().allMatch(CondEval::hit);
            if (!d.hit) {
                // Chưa quyết định được vì thiếu mục tiêu → báo lý do thay vì im lặng
                boolean undecided = someUnknown && (any || known.stream().allMatch(CondEval::hit));
                if (undecided) {
                    LinkedHashSet<String> need = new LinkedHashSet<>();
                    for (CondEval c : conds) if (c.unknown() && "target".equals(c.vs())) need.add(Labels.metric(c.metric()));
                    if (!need.isEmpty()) {
                        String acc = obj.accountName != null ? obj.accountName : obj.accountId != null ? obj.accountId : "";
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
            RuleMark mark = state.mark(rule.getId() == null ? "" : rule.getId(), obj.id);
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
            d.eligible = true;
            d.action = action.isNotify()
                    ? action.withMessage(String.join(any ? " HOẶC " : " VÀ ",
                            conds.stream().filter(CondEval::hit).map(c -> condSentence(c, range)).toList()))
                    : action;
            d.plan = executor.plan(obj, d.action, ActCtx.of(LogKind.RULE, rule.getId(), rule.getName()));
            d.status = DecisionStatus.of(d.plan.kind());
            switch (d.plan.kind()) {
                case SKIP -> { d.code = d.plan.code(); d.reason = d.plan.reason(); }
                case NOOP -> d.reason = "Không có gì để thay đổi (đã ở trạng thái/ngân sách mong muốn)";
                case ERROR -> d.reason = d.plan.reason();
                case DO -> { }
            }
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
        double count = metricValue(d.metrics, m);
        int h = -1;
        for (int i = 0; i < steps.size(); i++) if (count >= steps.get(i).count()) h = i;
        double refCount = steps.isEmpty() ? 1 : steps.get(Math.max(h, 0)).count();
        d.value = count;
        d.conds = List.of(new CondEval(m, ">", null, null, null, count, refCount - 1, false, h >= 0, null, null, null, h + 1,
                (int) refCount));
        if (h < 0) { d.reason = "Chưa có " + Fmt.num(refCount) + " " + unitWord(m) + " (đang có " + Fmt.num(count) + ")"; return; }
        d.hit = true;
        d.ladder = new Ladder(h, count, steps.get(h).count(), m);
        RuleMark mark = state.mark(rule.getId() == null ? "" : rule.getId(), obj.id);
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
        d.eligible = true;
        Rule.Step t = steps.get(h);
        d.action = Action.budget("amount".equals(t.mode()) ? BudgetMode.ADD : BudgetMode.PERCENT, t.value(), rule.getMaxBudget(), 0);
        d.planCtx = ActCtx.of(LogKind.RULE, rule.getId(), rule.getName()).ladder(!Boolean.FALSE.equals(rule.getIncludeLearning()));
        d.plan = executor.plan(obj, d.action, d.planCtx);
        d.status = DecisionStatus.of(d.plan.kind());
        switch (d.plan.kind()) {
            case SKIP -> { d.code = d.plan.code(); d.reason = d.plan.reason(); }
            case NOOP -> d.reason = "Đã chạm trần ngân sách " + Fmt.money(rule.getMaxBudget());
            case ERROR -> d.reason = d.plan.reason();
            case DO -> { }
        }
    }
}
