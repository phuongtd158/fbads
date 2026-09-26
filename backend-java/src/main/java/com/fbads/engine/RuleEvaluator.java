package com.fbads.engine;

import com.fbads.automation.Condition;
import com.fbads.automation.Rule;
import com.fbads.common.Fmt;
import com.fbads.engine.state.EngineState;
import com.fbads.engine.state.RuleMark;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.Delivery;
import com.fbads.facebook.Metrics;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Đánh giá một rule trên danh sách camp/nhóm QC (không thay đổi gì; dùng cho cả chạy thật lẫn Xem trước).
 * Bản Java của evaluateRule() + metricValue() trong lib/engine.js.
 */
@Component
public class RuleEvaluator {
    /** Kết quả xét 1 điều kiện cho 1 camp */
    public record CondEval(String metric, String op, String vs, Double factor, double actual, Double threshold, boolean unknown, boolean hit) {}

    /** Kết quả xét rule cho 1 camp. status: match | nomatch | skip | nochange | error */
    public static final class Decision {
        public AdObject obj;
        public String range;
        public Metrics metrics;
        public Double value;
        public boolean hit, eligible;
        public String status = "nomatch", code = "", reason = "";
        public Plan plan;
        public List<CondEval> conds = List.of();
        public Action action;

        Decision skip(String code, String reason) { this.status = "skip"; this.code = code; this.reason = reason; return this; }
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

    /** Ngưỡng của 1 điều kiện cho 1 camp: số cụ thể, hoặc mục tiêu của tài khoản × factor%. null = tài khoản chưa đặt mục tiêu. */
    private Double thresholdOf(Condition c, AdObject obj) {
        if (!c.vsTarget()) return c.value() == null ? 0 : c.value();
        double t = settings.get().target(obj.accountId, "spend".equals(c.metric()) ? "cpa" : c.metric()); // chi tiêu so với CPA mục tiêu
        double f = c.factor() == null || c.factor() == 0 ? 100 : c.factor();
        return t > 0 ? t * f / 100 : null;
    }

    private static String fmtTh(CondEval c) {
        return "roas".equals(c.metric()) || "frequency".equals(c.metric()) ? Fmt.fixed2(c.threshold())
                : "ctr".equals(c.metric()) ? Fmt.fixed2(c.threshold()) + "%" : Fmt.money(c.threshold());
    }

    static String condSentence(CondEval c, String range) {
        return Labels.metric(c.metric()) + " " + Labels.range(range) + " = " + Labels.show(c.actual(), c.metric()) + " "
                + (">".equals(c.op()) ? "lớn hơn" : "nhỏ hơn") + " ngưỡng " + fmtTh(c)
                + ("target".equals(c.vs()) ? " (" + Fmt.num(c.factor()) + "% " + ("spend".equals(c.metric()) ? "CPA " : "") + "mục tiêu)" : "");
    }

    public static Action actionOf(Rule rule) {
        if ("pause".equals(rule.getAction())) return Action.off();
        if ("notify".equals(rule.getAction())) return new Action("notify", null, 0, 0, 0, null);
        int sign = "increase".equals(rule.getAction()) ? 1 : -1;
        if ("amount".equals(rule.getBudgetMode())) return new Action("budget", "add", sign * rule.getAmount(), rule.getMaxBudget(), rule.getMinBudget(), null);
        return new Action("budget", "percent", sign * rule.getPct(), rule.getMaxBudget(), rule.getMinBudget(), null);
    }

    public List<Decision> evaluate(Rule rule, List<AdObject> objs, Function<String, Map<String, Metrics>> mapFor, Delivery.View running) {
        EngineClock.Now now = clock.now();
        long nowMs = clock.millis();
        String range = rule.getRange() == null || rule.getRange().isEmpty() ? "today" : rule.getRange();
        Map<String, Metrics> map = mapFor.apply(range);
        if (map == null) map = Map.of();
        String level = rule.getLevel() == null || rule.getLevel().isEmpty() ? "campaign" : rule.getLevel();
        boolean hasWindow = !rule.getFrom().isEmpty() && !rule.getTo().isEmpty();
        boolean outside = hasWindow && (now.minutes() < EngineClock.toMin(rule.getFrom()) || now.minutes() > EngineClock.toMin(rule.getTo()));
        List<String> accs = rule.getAccountIds() == null ? List.of() : rule.getAccountIds();
        // luôn xét cả effective: mục vừa bị rule trước tắt trong cùng lượt đã được cập nhật effective = PAUSED
        java.util.function.Predicate<AdObject> isRunning = o -> o.isActive() && (running == null || running.running(o));
        List<AdObject> list = rule.isAllActive()
                ? objs.stream().filter(o -> level.equals(o.level) && isRunning.test(o) && (accs.isEmpty() || accs.contains(o.accountId))).toList()
                : objs.stream().filter(o -> rule.getTargets() != null && rule.getTargets().contains(o.id)).toList();
        List<Condition> conditions = rule.conditionList();
        boolean any = "any".equals(rule.getMatch());
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
                d.skip("inactive", ("camp".equals(obj.unit()) ? "Camp" : "Nhóm QC") + " không đang chạy" + (why.isEmpty() ? "" : " (" + why + ")"));
                continue;
            }
            if (action.isBudget() && obj.dailyBudget == null) { d.skip("nobudget", ActionExecutor.noBudgetReason(obj)); continue; }
            if (outside) { d.skip("window", "Ngoài khung giờ của rule (" + rule.getFrom() + "–" + rule.getTo() + ")"); continue; }
            if (d.metrics.spend() < rule.getMinSpend()) {
                d.skip("minspend", "Chưa đủ chi tiêu tối thiểu (" + Fmt.money(d.metrics.spend()) + " < " + Fmt.money(rule.getMinSpend()) + ")");
                continue;
            }
            // Từng điều kiện rồi gộp bằng VÀ (mặc định) hoặc HOẶC. So với mục tiêu mà tài khoản chưa đặt mục tiêu → "chưa biết".
            List<CondEval> conds = new ArrayList<>();
            for (Condition c : conditions) {
                double actual = metricValue(d.metrics, c.metric());
                Double th = thresholdOf(c, obj);
                boolean unknown = th == null;
                boolean hit = !unknown && (">".equals(c.op()) ? actual > th : actual < th);
                conds.add(new CondEval(c.metric(), c.op(), c.vs(), c.factor(), actual, th, unknown, hit));
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
                    for (CondEval c : conds) if (c.unknown()) need.add(Labels.metric(c.metric()));
                    String acc = obj.accountName != null ? obj.accountName : obj.accountId != null ? obj.accountId : "";
                    d.skip("notarget", "Tài khoản " + acc + " chưa đặt mục tiêu " + String.join(", ", need) + " (Cài đặt → Mục tiêu)");
                }
                continue;
            }
            RuleMark mark = state.mark(rule.getId() == null ? "" : rule.getId(), obj.id);
            long holdLeft = mark.holdUntil() - nowMs;
            if (holdLeft > 0) { d.skip("hold", "Tạm hoãn sau khi bạn hoàn tác (còn " + (long) Math.ceil(holdLeft / 3600e3) + " giờ)"); continue; }
            long left = mark.lastRun() + (long) (rule.getCooldownHours() * 3600e3) - nowMs;
            if (left > 0) { d.skip("cooldown", "Đang nghỉ giữa hai lần (còn khoảng " + (long) Math.ceil(left / 3600e3) + " giờ)"); continue; }
            d.eligible = true;
            d.action = action.isNotify()
                    ? action.withMessage(String.join(any ? " HOẶC " : " VÀ ", conds.stream().filter(CondEval::hit).map(c -> condSentence(c, range)).toList()))
                    : action;
            d.plan = executor.plan(obj, d.action, ActCtx.of("rule", rule.getId(), rule.getName()));
            d.status = switch (d.plan.kind()) { case "do" -> "match"; case "skip" -> "skip"; case "error" -> "error"; default -> "nochange"; };
            if (d.plan.is("skip")) { d.code = d.plan.code(); d.reason = d.plan.reason(); }
            if (d.plan.is("noop")) d.reason = "Không có gì để thay đổi (đã ở trạng thái/ngân sách mong muốn)";
            if (d.plan.is("error")) d.reason = d.plan.reason();
        }
        return out;
    }
}
