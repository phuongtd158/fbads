package com.fbads.engine;

import com.fbads.client.RateLimits;
import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.dto.Condition;
import com.fbads.dto.Metrics;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogKind;
import com.fbads.entity.MatchMode;
import com.fbads.entity.Rule;
import com.fbads.entity.RuleAction;
import com.fbads.entity.RuleRange;
import com.fbads.entity.RuleResume;
import com.fbads.repository.RuleRepository;
import com.fbads.service.EngineState;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookInsights;
import com.fbads.service.facebook.FacebookObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Chạy rule theo chu kỳ, bật lại camp theo hẹn, xem trước rule và thống kê hoạt động của rule. */
@Service
public class RuleRunner {
    private static final Logger log = LoggerFactory.getLogger(RuleRunner.class);

    private final RuleRepository rules;
    private final FacebookObjects objects;
    private final FacebookInsights insights;
    private final RateLimits limits;
    private final RuleEvaluator evaluator;
    private final ActionExecutor executor;
    private final KillSwitch killSwitch;
    private final EngineState state;
    private final EngineClock clock;
    private final SettingsService settings;
    private final LogService logs;

    public RuleRunner(RuleRepository rules, FacebookObjects objects, FacebookInsights insights, RateLimits limits,
                      RuleEvaluator evaluator, ActionExecutor executor, KillSwitch killSwitch, EngineState state,
                      EngineClock clock, SettingsService settings, LogService logs) {
        this.rules = rules;
        this.objects = objects;
        this.insights = insights;
        this.limits = limits;
        this.evaluator = evaluator;
        this.executor = executor;
        this.killSwitch = killSwitch;
        this.state = state;
        this.clock = clock;
        this.settings = settings;
        this.logs = logs;
    }

    private static Double finite(double v) { return Double.isFinite(v) ? v : null; }

    private static RuleRange rangeOf(Rule r) { return r.getRange() == null ? RuleRange.TODAY : r.getRange(); }

    /** Số liệu của mọi khoảng các rule cần: khoảng tính của rule và các khoảng so sánh trong điều kiện */
    private Map<String, Map<String, Metrics>> loadMaps(List<Rule> list, boolean force) {
        LinkedHashSet<String> ranges = new LinkedHashSet<>();
        for (Rule r : list) {
            ranges.add(rangeOf(r).code());
            for (Condition c : r.conditionList()) if (c.vsRange()) ranges.add(c.compareRange());
        }
        Map<String, Map<String, Metrics>> maps = new LinkedHashMap<>();
        for (String range : ranges) maps.put(range, insights.rangeMetrics(range, force));
        return maps;
    }

    /** Các trường thêm của 1 điều kiện: khoảng so sánh, bậc ngưỡng theo kết quả, bậc của rule tăng theo bậc */
    private static void extras(Map<String, Object> m, RuleEvaluator.CondEval c) {
        if (c.compareRange() != null) m.put("compareRange", c.compareRange());
        if (c.tierMetric() != null) {
            m.put("tierMetric", c.tierMetric()); m.put("tierCount", c.tierCount());
            m.put("tierAt", c.tier() != null ? c.tier().count() : null);
        }
        if (c.ladderNeed() != null && c.ladderNeed() != 0) { m.put("ladderStep", c.ladderStep()); m.put("ladderNeed", c.ladderNeed()); }
    }

    public void runRules() {
        List<AdObject> objs = objects.listObjects(true);
        killSwitch.check(objs);
        List<Rule> active = rules.findByEnabledTrueOrderBySeqAsc();
        if (active.isEmpty()) return;
        // Facebook đang giới hạn số lần gọi → chỉ có số liệu cũ: không quyết định dựa trên nó, đợi lượt sau
        if (objects.isStale()) { log.info("Bỏ qua lượt kiểm tra rule: Facebook đang giới hạn số lần gọi, số liệu chưa cập nhật."); return; }
        Map<String, Map<String, Metrics>> maps = loadMaps(active, true);
        Delivery.View running = new Delivery.View(objs);
        for (Rule rule : active) {
            for (RuleEvaluator.Decision d : evaluator.evaluate(rule, objs, maps, running)) {
                if (!d.eligible) continue;
                // Chỉ bắt đầu "thời gian nghỉ" khi thật sự có hành động/lỗi; bỏ qua (đang học, chạm giới hạn ngày) thì xét lại lần sau
                if (d.plan.is(PlanKind.DO) || d.plan.is(PlanKind.ERROR)) state.setLastRun(rule.getId(), d.obj.id, clock.millis());
                String range = d.range == RuleRange.TODAY ? "" : " " + Labels.range(d.range);
                RuleEvaluator.CondEval c0 = d.conds.getFirst();
                String what = d.ladder != null
                        ? "Bậc " + (d.ladder.step() + 1) + ": có " + Fmt.num(d.ladder.count()) + " "
                                + RuleEvaluator.unitWord(d.ladder.metric())
                        : d.conds.size() == 1
                        ? Labels.metric(c0.metric()) + range + " " + Labels.show(c0.actual(), c0.metric())
                        : String.join(" · ", d.conds.stream()
                                .map(c -> Labels.metric(c.metric()) + " " + Labels.show(c.actual(), c.metric())).toList())
                          + (range.isEmpty() ? "" : " –" + range);
                ActResult r = executor.act(d.obj, d.action, "Rule: " + rule.getName() + " [" + what + "]",
                        (d.planCtx != null ? d.planCtx : ActCtx.of(LogKind.RULE, rule.getId(),
                                rule.getName())).withCondition(condition(rule, d, c0)));
                if (d.ladder != null && r == ActResult.OK)
                    state.setLadder(rule.getId(), d.obj.id, clock.now().date(), d.ladder.step(), clock.millis());
                // Rule tắt có hẹn bật lại: ghi nhớ để bật lại vào giờ hẹn ngày hôm sau (chạy thử thì camp không bị tắt thật nên không cần)
                if (r == ActResult.OK && d.action.type() == ActionType.OFF && "nextday".equals(rule.getResume()) && !settings.get().isDry())
                    state.addResume(rule.getId(), d.obj.id, clock.now().date());
            }
        }
    }

    /** Điều kiện đã khớp, lưu vào nhật ký (6 trường đầu = điều kiện đầu tiên, giữ cho nhật ký cũ; conditions = đầy đủ) */
    private static Map<String, Object> condition(Rule rule, RuleEvaluator.Decision d, RuleEvaluator.CondEval c0) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("metric", c0.metric()); c.put("op", c0.op()); c.put("range", d.range.code()); c.put("threshold", c0.threshold());
        c.put("actual", finite(c0.actual())); c.put("actualInf", c0.actual() == Double.POSITIVE_INFINITY);
        c.put("minSpend", rule.getMinSpend()); c.put("spend", d.metrics.spend()); c.put("cooldownHours", rule.getCooldownHours());
        c.put("match", rule.getMatch() == MatchMode.ANY ? "any" : "all");
        List<Map<String, Object>> list = new ArrayList<>();
        for (RuleEvaluator.CondEval x : d.conds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metric", x.metric()); m.put("op", x.op());
            if (x.vs() != null) m.put("vs", x.vs());
            if (x.factor() != null) m.put("factor", x.factor());
            m.put("threshold", x.threshold()); m.put("actual", finite(x.actual()));
            m.put("actualInf", x.actual() == Double.POSITIVE_INFINITY);
            m.put("hit", x.hit()); m.put("unknown", x.unknown());
            extras(m, x);
            list.add(m);
        }
        c.put("conditions", list);
        if (d.ladder != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("step", d.ladder.step() + 1); l.put("count", d.ladder.count()); l.put("need", d.ladder.need());
            l.put("metric", d.ladder.metric());
            l.put("total", rule.getSteps() == null ? 0 : rule.getSteps().size());
            c.put("ladder", l);
        }
        return c;
    }

    /** Bật lại các mục mà rule đã tắt, khi tới giờ hẹn của một ngày sau ngày tắt. Bỏ hẹn nếu rule bị xoá/đổi, hoặc mục đã được bật lại. */
    public void tickResumes() {
        List<RuleResume> pending = state.resumes();
        if (pending.isEmpty()) return;
        EngineClock.Now now = clock.now();
        Map<String, Rule> byId = new LinkedHashMap<>();
        for (Rule r : rules.findAll()) byId.put(r.getId(), r);
        List<RuleResume> due = new ArrayList<>();
        for (RuleResume p : pending) {
            Rule rule = byId.get(p.getKey().ruleId());
            if (rule == null || rule.getAction() != RuleAction.PAUSE || !"nextday".equals(rule.getResume())) {
                state.removeResume(p.getKey());
                continue;
            }
            String at = rule.getResumeAt() == null || rule.getResumeAt().isEmpty() ? "06:00" : rule.getResumeAt();
            if (now.date().compareTo(p.getOffDate()) > 0 && now.minutes() >= EngineClock.toMin(at)) due.add(p);
        }
        if (due.isEmpty()) return;
        List<AdObject> objs = objects.listObjects(true);
        if (limits.blocked()) return; // Facebook đang giới hạn: thử lại ở lượt sau
        for (RuleResume p : due) {
            Rule rule = byId.get(p.getKey().ruleId());
            AdObject obj = objs.stream().filter(o -> o.id.equals(p.getKey().objId())).findFirst().orElse(null);
            state.removeResume(p.getKey());
            state.setLastRun(rule.getId(), p.getKey().objId(), 0); // ngày mới: rule được xét lại ngay
            if (obj == null || "ACTIVE".equals(obj.status)) continue;
            String at = rule.getResumeAt() == null || rule.getResumeAt().isEmpty() ? "06:00" : rule.getResumeAt();
            executor.act(obj, Action.on(), "Rule: " + rule.getName() + " [bật lại theo hẹn " + at + "]",
                    ActCtx.of(LogKind.RULE, rule.getId(), rule.getName()));
        }
    }

    /** Xem trước: rule này đang khớp camp nào NGAY BÂY GIỜ (không thay đổi gì, không cập nhật thời gian nghỉ) */
    public Map<String, Object> preview(Rule rule) {
        List<AdObject> objs = objects.listObjects(false);
        Map<String, Map<String, Metrics>> maps = loadMaps(List.of(rule), false);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RuleEvaluator.Decision d : evaluator.evaluate(rule, objs, maps, new Delivery.View(objs))) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", d.obj.id); i.put("name", d.obj.name); i.put("level", d.obj.level.code()); i.put("effective", d.obj.effective);
            i.put("learning", d.obj.learning); i.put("budget", d.obj.dailyBudget);
            i.put("status", d.status.code()); i.put("code", d.code); i.put("reason", d.reason); i.put("hit", d.hit);
            i.put("value", d.value == null ? null : finite(d.value)); i.put("inf", d.value != null && d.value == Double.POSITIVE_INFINITY);
            i.put("spend", d.metrics.spend()); i.put("results", d.metrics.results());
            List<Map<String, Object>> conds = new ArrayList<>();
            for (RuleEvaluator.CondEval c : d.conds) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("metric", c.metric()); m.put("op", c.op()); m.put("vs", c.vs()); m.put("factor", c.factor());
                m.put("threshold", c.threshold());
                m.put("actual", finite(c.actual())); m.put("inf", c.actual() == Double.POSITIVE_INFINITY);
                m.put("hit", c.hit()); m.put("unknown", c.unknown());
                extras(m, c);
                conds.add(m);
            }
            i.put("conds", conds);
            i.put("result", d.plan != null && d.plan.is(PlanKind.DO) ? Map.of("detail", d.plan.detail(), "notify", d.plan.notifyOnly()) : null);
            items.add(i);
        }
        AppSettings s = settings.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", rangeOf(rule).code());
        out.put("mode", s.mode());
        out.put("willChange", !s.isDry());
        out.put("items", items);
        out.put("counts", Map.of("match", items.stream().filter(i -> "match".equals(i.get("status"))).count(), "total", items.size()));
        return out;
    }

    /**
     * Hoạt động gần đây của mỗi rule: số lần tác động / lỗi trong 7 ngày, lần gần nhất, số mục đang chờ bật lại. Không
     * tính dòng "Bỏ qua".
     */
    public Map<String, Object> activity() {
        long since = System.currentTimeMillis() - 7 * 86_400_000L;
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Function<String, Map<String, Object>> get = id -> out.computeIfAbsent(id, k -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("acts", 0); a.put("errors", 0); a.put("last", null); a.put("resumePending", 0);
            return a;
        });
        for (LogEntry l : logs.ofKind(LogKind.RULE)) { // mới nhất trước
            if (l.getRefId() == null || Boolean.TRUE.equals(l.getSkipped())) continue;
            Map<String, Object> a = get.apply(l.getRefId());
            if (a.get("last") == null) {
                Map<String, Object> last = new LinkedHashMap<>();
                last.put("ts", l.getTs().toString()); last.put("name", l.getName()); last.put("detail", l.getDetail());
                last.put("ok", l.succeeded()); last.put("dry", Boolean.TRUE.equals(l.getDry()));
                a.put("last", last);
            }
            if (l.getTs().toEpochMilli() < since) continue;
            if (!l.succeeded()) a.put("errors", (int) a.get("errors") + 1);
            else a.put("acts", (int) a.get("acts") + 1);
        }
        for (RuleResume p : state.resumes()) {
            Map<String, Object> a = get.apply(p.getKey().ruleId());
            a.put("resumePending", (int) a.get("resumePending") + 1);
        }
        return new LinkedHashMap<>(out);
    }
}
