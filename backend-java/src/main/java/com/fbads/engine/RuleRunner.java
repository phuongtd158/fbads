package com.fbads.engine;

import com.fbads.automation.Rule;
import com.fbads.automation.RuleRepository;
import com.fbads.engine.state.EngineState;
import com.fbads.engine.state.RuleResume;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.Delivery;
import com.fbads.facebook.FacebookService;
import com.fbads.facebook.Metrics;
import com.fbads.facebook.RateLimits;
import com.fbads.logs.LogEntry;
import com.fbads.logs.LogService;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Chạy rule theo chu kỳ, bật lại camp theo hẹn, xem trước rule và thống kê hoạt động của rule. */
@Service
public class RuleRunner {
    private static final Logger log = LoggerFactory.getLogger(RuleRunner.class);

    private final RuleRepository rules;
    private final FacebookService fb;
    private final RateLimits limits;
    private final RuleEvaluator evaluator;
    private final ActionExecutor executor;
    private final KillSwitch killSwitch;
    private final EngineState state;
    private final EngineClock clock;
    private final SettingsService settings;
    private final LogService logs;

    public RuleRunner(RuleRepository rules, FacebookService fb, RateLimits limits, RuleEvaluator evaluator, ActionExecutor executor,
                      KillSwitch killSwitch, EngineState state, EngineClock clock, SettingsService settings, LogService logs) {
        this.rules = rules;
        this.fb = fb;
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

    private static String rangeOf(Rule r) { return r.getRange() == null || r.getRange().isEmpty() ? "today" : r.getRange(); }

    public void runRules() {
        List<AdObject> objs = fb.listObjects(true);
        killSwitch.check(objs);
        List<Rule> active = rules.findByEnabledTrueOrderBySeqAsc();
        if (active.isEmpty()) return;
        // Facebook đang giới hạn số lần gọi → chỉ có số liệu cũ: không quyết định dựa trên nó, đợi lượt sau
        if (fb.isStale()) { log.info("Bỏ qua lượt kiểm tra rule: Facebook đang giới hạn số lần gọi, số liệu chưa cập nhật."); return; }
        Map<String, Map<String, Metrics>> maps = new LinkedHashMap<>();
        for (String range : new LinkedHashSet<>(active.stream().map(RuleRunner::rangeOf).toList())) maps.put(range, fb.rangeMetrics(range, true));
        Delivery.View running = new Delivery.View(objs);
        for (Rule rule : active) {
            for (RuleEvaluator.Decision d : evaluator.evaluate(rule, objs, maps::get, running)) {
                if (!d.eligible) continue;
                // Chỉ bắt đầu "thời gian nghỉ" khi thật sự có hành động/lỗi; bỏ qua (đang học, chạm giới hạn ngày) thì xét lại lần sau
                if (d.plan.is("do") || d.plan.is("error")) state.setLastRun(rule.getId(), d.obj.id, clock.millis());
                String range = d.range.equals("today") ? "" : " " + Labels.range(d.range);
                RuleEvaluator.CondEval c0 = d.conds.getFirst();
                String what = d.conds.size() == 1
                        ? Labels.metric(c0.metric()) + range + " " + Labels.show(c0.actual(), c0.metric())
                        : String.join(" · ", d.conds.stream().map(c -> Labels.metric(c.metric()) + " " + Labels.show(c.actual(), c.metric())).toList())
                          + (range.isEmpty() ? "" : " –" + range);
                String r = executor.act(d.obj, d.action, "Rule: " + rule.getName() + " [" + what + "]",
                        ActCtx.of("rule", rule.getId(), rule.getName()).withCondition(condition(rule, d, c0)));
                // Rule tắt có hẹn bật lại: ghi nhớ để bật lại vào giờ hẹn ngày hôm sau (chạy thử thì camp không bị tắt thật nên không cần)
                if ("ok".equals(r) && "off".equals(d.action.type()) && "nextday".equals(rule.getResume()) && !settings.get().isDry())
                    state.addResume(rule.getId(), d.obj.id, clock.now().date());
            }
        }
    }

    /** Điều kiện đã khớp, lưu vào nhật ký (6 trường đầu = điều kiện đầu tiên, giữ cho nhật ký cũ; conditions = đầy đủ) */
    private static Map<String, Object> condition(Rule rule, RuleEvaluator.Decision d, RuleEvaluator.CondEval c0) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("metric", c0.metric()); c.put("op", c0.op()); c.put("range", d.range); c.put("threshold", c0.threshold());
        c.put("actual", finite(c0.actual())); c.put("actualInf", c0.actual() == Double.POSITIVE_INFINITY);
        c.put("minSpend", rule.getMinSpend()); c.put("spend", d.metrics.spend()); c.put("cooldownHours", rule.getCooldownHours());
        c.put("match", "any".equals(rule.getMatch()) ? "any" : "all");
        List<Map<String, Object>> list = new ArrayList<>();
        for (RuleEvaluator.CondEval x : d.conds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metric", x.metric()); m.put("op", x.op());
            if (x.vs() != null) m.put("vs", x.vs());
            if (x.factor() != null) m.put("factor", x.factor());
            m.put("threshold", x.threshold()); m.put("actual", finite(x.actual())); m.put("actualInf", x.actual() == Double.POSITIVE_INFINITY);
            m.put("hit", x.hit()); m.put("unknown", x.unknown());
            list.add(m);
        }
        c.put("conditions", list);
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
            if (rule == null || !"pause".equals(rule.getAction()) || !"nextday".equals(rule.getResume())) { state.removeResume(p.getKey()); continue; }
            String at = rule.getResumeAt() == null || rule.getResumeAt().isEmpty() ? "06:00" : rule.getResumeAt();
            if (now.date().compareTo(p.getOffDate()) > 0 && now.minutes() >= EngineClock.toMin(at)) due.add(p);
        }
        if (due.isEmpty()) return;
        List<AdObject> objs = fb.listObjects(true);
        if (limits.blocked()) return; // Facebook đang giới hạn: thử lại ở lượt sau
        for (RuleResume p : due) {
            Rule rule = byId.get(p.getKey().ruleId());
            AdObject obj = objs.stream().filter(o -> o.id.equals(p.getKey().objId())).findFirst().orElse(null);
            state.removeResume(p.getKey());
            state.setLastRun(rule.getId(), p.getKey().objId(), 0); // ngày mới: rule được xét lại ngay
            if (obj == null || "ACTIVE".equals(obj.status)) continue;
            String at = rule.getResumeAt() == null || rule.getResumeAt().isEmpty() ? "06:00" : rule.getResumeAt();
            executor.act(obj, Action.on(), "Rule: " + rule.getName() + " [bật lại theo hẹn " + at + "]", ActCtx.of("rule", rule.getId(), rule.getName()));
        }
    }

    /** Xem trước: rule này đang khớp camp nào NGAY BÂY GIỜ (không thay đổi gì, không cập nhật thời gian nghỉ) */
    public Map<String, Object> preview(Rule rule) {
        List<AdObject> objs = fb.listObjects(false);
        Map<String, Metrics> map = fb.rangeMetrics(rangeOf(rule), false);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RuleEvaluator.Decision d : evaluator.evaluate(rule, objs, r -> map, new Delivery.View(objs))) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", d.obj.id); i.put("name", d.obj.name); i.put("level", d.obj.level); i.put("effective", d.obj.effective);
            i.put("learning", d.obj.learning); i.put("budget", d.obj.dailyBudget);
            i.put("status", d.status); i.put("code", d.code); i.put("reason", d.reason); i.put("hit", d.hit);
            i.put("value", d.value == null ? null : finite(d.value)); i.put("inf", d.value != null && d.value == Double.POSITIVE_INFINITY);
            i.put("spend", d.metrics.spend()); i.put("results", d.metrics.results());
            List<Map<String, Object>> conds = new ArrayList<>();
            for (RuleEvaluator.CondEval c : d.conds) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("metric", c.metric()); m.put("op", c.op()); m.put("vs", c.vs()); m.put("factor", c.factor()); m.put("threshold", c.threshold());
                m.put("actual", finite(c.actual())); m.put("inf", c.actual() == Double.POSITIVE_INFINITY); m.put("hit", c.hit()); m.put("unknown", c.unknown());
                conds.add(m);
            }
            i.put("conds", conds);
            i.put("result", d.plan != null && d.plan.is("do") ? Map.of("detail", d.plan.detail(), "notify", d.plan.notifyOnly()) : null);
            items.add(i);
        }
        AppSettings s = settings.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", rangeOf(rule));
        out.put("mode", s.mode());
        out.put("willChange", !s.isDry());
        out.put("items", items);
        out.put("counts", Map.of("match", items.stream().filter(i -> "match".equals(i.get("status"))).count(), "total", items.size()));
        return out;
    }

    /** Hoạt động gần đây của mỗi rule: số lần tác động / lỗi trong 7 ngày, lần gần nhất, số mục đang chờ bật lại. Không tính dòng "Bỏ qua". */
    public Map<String, Object> activity() {
        long since = System.currentTimeMillis() - 7 * 86_400_000L;
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        java.util.function.Function<String, Map<String, Object>> get = id -> out.computeIfAbsent(id, k -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("acts", 0); a.put("errors", 0); a.put("last", null); a.put("resumePending", 0);
            return a;
        });
        for (LogEntry l : logs.ofKind("rule")) { // mới nhất trước
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
