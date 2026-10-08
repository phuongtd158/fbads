package com.fbads.rule;

import com.fbads.client.RateLimits;
import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.engine.ActCtx;
import com.fbads.engine.ActResult;
import com.fbads.engine.Action;
import com.fbads.engine.ActionExecutor;
import com.fbads.engine.ActionType;
import com.fbads.engine.Delivery;
import com.fbads.engine.EngineClock;
import com.fbads.engine.KillSwitch;
import com.fbads.engine.Labels;
import com.fbads.engine.PlanKind;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogCondition;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogKind;
import com.fbads.rule.RuleActivity.LastRun;
import com.fbads.rule.RulePreview.Counts;
import com.fbads.rule.RulePreview.PreviewCond;
import com.fbads.rule.RulePreview.PreviewItem;
import com.fbads.rule.RulePreview.PreviewResult;
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
import java.util.Objects;

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
                if (!d.eligible()) continue;
                // Chỉ bắt đầu "thời gian nghỉ" khi thật sự có hành động/lỗi; bỏ qua (đang học, chạm giới hạn ngày) thì xét lại lần sau
                if (d.plan().is(PlanKind.DO) || d.plan().is(PlanKind.ERROR)) state.setLastRun(rule.getId(), d.obj().id(), clock.millis());
                String range = d.range() == RuleRange.TODAY ? "" : " " + Labels.range(d.range());
                RuleEvaluator.CondEval c0 = d.conds().getFirst();
                String what = d.ladder() != null
                        ? "Bậc " + (d.ladder().step() + 1) + ": có " + Fmt.num(d.ladder().count()) + " "
                                + RuleEvaluator.unitWord(d.ladder().metric())
                        : d.conds().size() == 1
                        ? Labels.metric(c0.metric()) + range + " " + Labels.show(c0.actual(), c0.metric())
                        : String.join(" · ", d.conds().stream()
                                .map(c -> Labels.metric(c.metric()) + " " + Labels.show(c.actual(), c.metric())).toList())
                          + (range.isEmpty() ? "" : " –" + range);
                ActResult r = executor.act(d.obj(), d.action(), "Rule: " + rule.getName() + " [" + what + "]",
                        (d.planCtx() != null ? d.planCtx() : ActCtx.of(LogKind.RULE, rule.getId(),
                                rule.getName())).withCondition(condition(rule, d, c0)));
                if (d.ladder() != null && r == ActResult.OK)
                    state.setLadder(rule.getId(), d.obj().id(), clock.now().date(), d.ladder().step(), clock.millis());
                // Rule tắt có hẹn bật lại: ghi nhớ để bật lại vào giờ hẹn ngày hôm sau (chạy thử thì camp không bị tắt thật nên không cần)
                if (r == ActResult.OK && d.action().type() == ActionType.OFF && "nextday".equals(rule.getResume()) && !settings.get().isDry())
                    state.addResume(rule.getId(), d.obj().id(), clock.now().date());
            }
        }
    }

    /** Điều kiện đã khớp, lưu vào nhật ký (6 trường đầu = điều kiện đầu tiên, giữ cho nhật ký cũ; conditions = đầy đủ) */
    private static LogCondition condition(Rule rule, RuleEvaluator.Decision d, RuleEvaluator.CondEval c0) {
        List<LogCondition.Hit> hits = d.conds().stream().map(RuleRunner::hit).toList();
        LogCondition.Ladder ladder = d.ladder() == null ? null : new LogCondition.Ladder(d.ladder().step() + 1, d.ladder().count(),
                d.ladder().need(), d.ladder().metric(), rule.getSteps() == null ? 0 : rule.getSteps().size());
        return new LogCondition(c0.metric(), c0.op(), d.range(), c0.threshold(), finite(c0.actual()), isInf(c0.actual()),
                rule.getMinSpend(), d.metrics().spend(), rule.getCooldownHours(),
                Objects.requireNonNullElse(rule.getMatch(), MatchMode.ALL), hits, ladder);
    }

    /** Một điều kiện trong nhật ký; bậc ngưỡng theo kết quả (tier…) và bậc của rule tăng theo bậc (ladder…) chỉ có khi dùng */
    private static LogCondition.Hit hit(RuleEvaluator.CondEval x) {
        boolean tiered = x.tierMetric() != null, laddered = x.ladderNeed() != null && x.ladderNeed() != 0;
        return new LogCondition.Hit(x.metric(), x.op(), x.vs(), x.factor(), x.threshold(), finite(x.actual()), isInf(x.actual()),
                x.hit(), x.unknown(), x.compareRange(), x.tierMetric(), tiered ? x.tierCount() : null,
                tiered && x.tier() != null ? x.tier().count() : null, laddered ? x.ladderStep() : null,
                laddered ? x.ladderNeed() : null);
    }

    private static boolean isInf(double v) { return v == Double.POSITIVE_INFINITY; }

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
            AdObject obj = objs.stream().filter(o -> o.id().equals(p.getKey().objId())).findFirst().orElse(null);
            state.removeResume(p.getKey());
            state.setLastRun(rule.getId(), p.getKey().objId(), 0); // ngày mới: rule được xét lại ngay
            if (obj == null || "ACTIVE".equals(obj.status())) continue;
            String at = rule.getResumeAt() == null || rule.getResumeAt().isEmpty() ? "06:00" : rule.getResumeAt();
            executor.act(obj, Action.on(), "Rule: " + rule.getName() + " [bật lại theo hẹn " + at + "]",
                    ActCtx.of(LogKind.RULE, rule.getId(), rule.getName()));
        }
    }

    /** Xem trước: rule này đang khớp camp nào NGAY BÂY GIỜ (không thay đổi gì, không cập nhật thời gian nghỉ) */
    public RulePreview preview(Rule rule) {
        List<AdObject> objs = objects.listObjects(false);
        Map<String, Map<String, Metrics>> maps = loadMaps(List.of(rule), false);
        List<PreviewItem> items = evaluator.evaluate(rule, objs, maps, new Delivery.View(objs)).stream()
                .map(RuleRunner::previewItem).toList();
        AppSettings s = settings.get();
        long matched = items.stream().filter(i -> i.status() == DecisionStatus.MATCH).count();
        return new RulePreview(rangeOf(rule).code(), s.mode(), !s.isDry(), items, new Counts(matched, items.size()), null);
    }

    private static PreviewItem previewItem(RuleEvaluator.Decision d) {
        AdObject o = d.obj();
        PreviewResult result = d.plan() != null && d.plan().is(PlanKind.DO) ? new PreviewResult(d.plan().detail(), d.plan().notifyOnly()) : null;
        return new PreviewItem(o.id(), o.name(), o.level(), o.effective(), o.learning(), o.dailyBudget(), d.status(), d.code(), d.reason(), d.hit(),
                d.value() == null ? null : finite(d.value()), d.value() != null && isInf(d.value()), d.metrics().spend(), d.metrics().results(),
                d.conds().stream().map(RuleRunner::previewCond).toList(), result);
    }

    /** Một điều kiện trong xem trước; bậc theo kết quả (tier…) và bậc của rule tăng theo bậc (ladder…) chỉ có khi dùng */
    private static PreviewCond previewCond(RuleEvaluator.CondEval c) {
        boolean tiered = c.tierMetric() != null, laddered = c.ladderNeed() != null && c.ladderNeed() != 0;
        return new PreviewCond(c.metric(), c.op(), c.vs(), c.factor(), c.threshold(), finite(c.actual()), isInf(c.actual()),
                c.hit(), c.unknown(), c.compareRange(), c.tierMetric(), tiered ? c.tierCount() : null,
                tiered && c.tier() != null ? c.tier().count() : null, laddered ? c.ladderStep() : null,
                laddered ? c.ladderNeed() : null);
    }

    /** Số đếm hoạt động của một rule trong lúc duyệt nhật ký */
    private static final class Tally {
        int acts, errors, resumePending;
        LastRun last;

        RuleActivity done() { return new RuleActivity(acts, errors, last, resumePending); }
    }

    /**
     * Hoạt động gần đây của mỗi rule: số lần tác động / lỗi trong 7 ngày, lần gần nhất, số mục đang chờ bật lại. Không
     * tính dòng "Bỏ qua".
     */
    public Map<String, RuleActivity> activity() {
        long since = System.currentTimeMillis() - 7 * 86_400_000L;
        Map<String, Tally> byRule = new LinkedHashMap<>();
        for (LogEntry l : logs.ofKind(LogKind.RULE)) { // mới nhất trước
            if (l.getRefId() == null || Boolean.TRUE.equals(l.getSkipped())) continue;
            Tally t = byRule.computeIfAbsent(l.getRefId(), k -> new Tally());
            if (t.last == null)
                t.last = new LastRun(l.getTs().toString(), l.getName(), l.getDetail(), l.succeeded(), Boolean.TRUE.equals(l.getDry()));
            if (l.getTs().toEpochMilli() < since) continue;
            if (l.succeeded()) t.acts++;
            else t.errors++;
        }
        for (RuleResume p : state.resumes()) byRule.computeIfAbsent(p.getKey().ruleId(), k -> new Tally()).resumePending++;
        Map<String, RuleActivity> out = new LinkedHashMap<>();
        byRule.forEach((id, t) -> out.put(id, t.done()));
        return out;
    }
}
