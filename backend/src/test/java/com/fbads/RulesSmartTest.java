package com.fbads;

import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.engine.EngineClock;
import com.fbads.entity.LogCondition;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogKind;
import com.fbads.rule.Condition;
import com.fbads.rule.DecisionStatus;
import com.fbads.rule.Rule;
import com.fbads.rule.RuleEvaluator;
import com.fbads.rule.RulePreview.PreviewCond;
import com.fbads.rule.RulePreview;
import com.fbads.rule.RuleRange;
import com.fbads.rule.RuleRepository;
import com.fbads.rule.RuleRequest;
import com.fbads.rule.RuleRunner;
import com.fbads.rule.RuleService;
import com.fbads.rule.RuleValidator;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EngineState;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookState;
import com.fbads.validation.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule so sánh hai khoảng, ngưỡng chi tiêu theo số kết quả, rule tăng theo bậc kết quả
 * (bản Node: tests/rulesCompare, tests/rulesTiers, tests/rulesLadder).
 */
class RulesSmartTest extends IntegrationBase {
    @Autowired JsonMapper mapper;
    @Autowired RuleEvaluator evaluator;
    @Autowired RuleRunner runner;
    @Autowired RuleService ruleService;
    @Autowired RuleRepository ruleRepo;
    @Autowired EngineState state;
    @Autowired EngineClock clock;
    @Autowired SettingsService settings;
    @Autowired FacebookState fbState;
    @Autowired LogService logs;

    WorkspaceContext.Scope ws;
    final List<String> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        clock.setClock(Clock.fixed(Instant.parse("2031-03-04T13:00:00Z"), ZoneOffset.UTC)); // 20:00 giờ Việt Nam
        settings.update(s -> { s.setMock(true); s.setDryRun(true); s.setSkipLearning(true); s.setDailyChangeCapPct(30); });
        state.clearAll();
        fbState.resetMock();
        fbState.resetCache();
    }

    @AfterEach
    void tearDown() {
        created.forEach(ruleRepo::deleteById);
        state.clearAll();
        fbState.resetMock();
        fbState.resetCache();
        clock.setClock(Clock.systemUTC());
        ws.close();
    }

    // ----- dữ liệu dựng sẵn

    @SafeVarargs
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> with(Map<String, Object> base, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.putAll(map(kv));
        return m;
    }

    Result<Rule> validate(Map<String, Object> in) {
        return RuleValidator.validate(mapper.convertValue(in, RuleRequest.class), List.of(), List.of(), Map.of(), List.of());
    }

    static AdObject obj(String id, String level, double budget, boolean learning) {
        return AdObject.builder(id, id, AdLevel.from(level)).state("ACTIVE", "ACTIVE").budget(budget).account("a1", null, null)
                .learning(learning).build();
    }

    /** spend, results; leads/tin nhắn tuỳ chọn */
    static Metrics m(double spend, double results, double leads, double messages) {
        return new Metrics(spend, spend * 10, spend * 5, spend / 1000, results, results > 0 ? spend / results : null, 0, null, messages, 0, leads, 0, 0);
    }

    static Metrics m(double spend, double results) { return m(spend, results, 0, 0); }

    /** maps: khoảng → số liệu (hàm cho gọn khi mọi khoảng dùng chung số liệu) */
    List<RuleEvaluator.Decision> eval(Rule r, List<AdObject> objs, Function<String, Map<String, Metrics>> maps) {
        Map<String, Map<String, Metrics>> byRange = new HashMap<>();
        for (RuleRange range : RuleRange.values()) {
            Map<String, Metrics> m = maps.apply(range.code());
            if (m != null) byRange.put(range.code(), m);
        }
        return evaluator.evaluate(r, objs, byRange, null);
    }

    // ----- So sánh hai khoảng

    final Map<String, Object> cmpBase = map("name", "CPA tăng", "range", "today", "minSpend", 100000, "action", "notify", "cooldownHours", 24, "allActive", true);

    static Map<String, Object> cmp(Object... over) {
        return with(map("metric", "cpa", "op", ">", "vs", "range", "compareRange", "last_7d", "factor", 130), over);
    }

    Rule cmpRule(Map<String, Object> cond, Object... over) {
        Result<Rule> r = validate(with(with(cmpBase, "conditions", List.of(cond)), over));
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        r.value().setId("r1");
        return r.value();
    }

    @Test
    void compareValidation() {
        Result<Rule> ok = validate(with(cmpBase, "conditions", List.of(cmp())));
        assertThat(ok.ok()).as(ok.errors().toString()).isTrue();
        assertThat(ok.value().getConditions().getFirst()).isEqualTo(new Condition("cpa", ">", "range", "last_7d", 130.0, 0.0, null, null));
        assertThat(validate(with(cmpBase, "range", "last_7d", "conditions", List.of(cmp()))).ok()).as("khoảng so sánh trùng khoảng của rule").isFalse();
        assertThat(validate(with(cmpBase, "conditions", List.of(cmp("factor", 0)))).ok()).isFalse();
        assertThat(validate(with(cmpBase, "conditions", List.of(cmp("compareRange", "last_30d")))).ok()).isFalse();
        assertThat(validate(with(cmpBase, "conditions", List.of(cmp("metric", "ctr", "op", "<", "factor", 70)))).ok()).isTrue();
        // số liệu dạng tổng của hôm nay (chưa hết ngày) thì cảnh báo
        assertThat(validate(with(cmpBase, "conditions", List.of(cmp("metric", "spend")))).warnings()).anyMatch(w -> w.contains("mới tính đến giờ hiện tại"));
        assertThat(validate(with(cmpBase, "conditions", List.of(cmp()))).warnings()).noneMatch(w -> w.contains("mới tính đến giờ hiện tại"));
    }

    @Test
    void compareThreshold() {
        Condition c = new Condition("cpa", ">", "range", "last_7d", 130.0, 0.0, null, null);
        assertThat(RuleValidator.compareThreshold(c, 100000.0)).isEqualTo(130000.0);
        Condition r = new Condition("results", ">", "range", "last_7d", 50.0, 0.0, null, null);
        assertThat(RuleValidator.compareThreshold(r, 70.0)).isEqualTo(5.0); // 70 kết quả / 7 ngày = 10/ngày × 50%
        assertThat(RuleValidator.compareThreshold(c, 0.0)).isNull();
        assertThat(RuleValidator.compareThreshold(c, Double.POSITIVE_INFINITY)).isNull();
    }

    @Test
    void compareEvaluate() {
        Rule rule = cmpRule(cmp());
        Map<String, Map<String, Metrics>> maps = Map.of(
                "today", Map.of("c1", m(300000, 1), "c2", m(300000, 3)),          // c1: CPA 300k; c2: CPA 100k
                "last_7d", Map.of("c1", m(1400000, 14), "c2", m(1400000, 14)));   // CPA 7 ngày = 100k → ngưỡng 130k
        Map<String, RuleEvaluator.Decision> by = new HashMap<>();
        for (RuleEvaluator.Decision d : eval(rule, List.of(obj("c1", "campaign", 500000, false), obj("c2", "campaign", 500000, false)), maps::get)) by.put(d.obj().id(), d);
        assertThat(by.get("c1").status()).isEqualTo(DecisionStatus.MATCH);
        assertThat(by.get("c1").conds().getFirst().threshold()).isEqualTo(130000.0);
        assertThat(by.get("c1").plan().detail()).contains("130% CPA 7 ngày gần nhất");
        assertThat(by.get("c2").status()).isEqualTo(DecisionStatus.NOMATCH);

        // khoảng so sánh chưa có số liệu (camp mới) → bỏ qua, nói rõ lý do
        RuleEvaluator.Decision d = eval(rule, List.of(obj("c1", "campaign", 500000, false)),
                Map.of("today", Map.of("c1", m(300000, 1)), "last_7d", Map.<String, Metrics>of())::get).getFirst();
        assertThat(d.status()).isEqualTo(DecisionStatus.SKIP);
        assertThat(d.code()).isEqualTo("nobaseline");
        assertThat(d.reason()).contains("Chưa có số liệu CPA 7 ngày gần nhất");

        // số tin nhắn hôm qua thấp hơn 50% trung bình 3 ngày: 30/3 = 10/ngày → ngưỡng 5
        Rule msg = cmpRule(map("metric", "messages", "op", "<", "vs", "range", "compareRange", "last_3d", "factor", 50), "range", "yesterday");
        d = eval(msg, List.of(obj("c1", "campaign", 500000, false)),
                Map.of("yesterday", Map.of("c1", m(300000, 0, 0, 2)), "last_3d", Map.of("c1", m(900000, 0, 0, 30)))::get).getFirst();
        assertThat(d.conds().getFirst().threshold()).isEqualTo(5.0);
        assertThat(d.status()).isEqualTo(DecisionStatus.MATCH);
    }

    @Test
    void comparePreviewLoadsCompareRange() {
        RulePreview pv = ruleService.preview(mapper.convertValue(with(cmpBase, "range", "yesterday", "minSpend", 1,
                "conditions", List.of(cmp("metric", "spend", "factor", 50))), RuleRequest.class));
        assertThat(pv.items()).isNotEmpty();
        List<PreviewCond> c0 = pv.items().stream().map(i -> i.conds().getFirst()).toList();
        assertThat(c0).as("có ngưỡng tính từ số liệu 7 ngày").anyMatch(c -> c.threshold() != null);
        assertThat(c0.getFirst().compareRange()).isEqualTo("last_7d");
    }

    // ----- Ngưỡng chi tiêu theo số kết quả

    final Map<String, Object> tierBase = map("name", "r", "range", "today", "action", "decrease", "pct", 50, "cooldownHours", 24, "allActive", true, "level", "adset");

    static Map<String, Object> tierCond(Object... over) {
        return with(map("metric", "spend", "op", ">", "value", 150000, "tierMetric", "leads", "tiers", List.of(map("count", 2, "value", 200000))), over);
    }

    @Test
    void tiersValidation() {
        Result<Rule> r = validate(with(tierBase, "conditions", List.of(tierCond())));
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        assertThat(r.value().getConditions().getFirst())
                .isEqualTo(new Condition("spend", ">", null, null, null, 150000.0, "leads", List.of(new Condition.Tier(2, 200000))));
        // không có bậc thì điều kiện giữ nguyên như cũ
        assertThat(validate(with(tierBase, "conditions", List.of(map("metric", "spend", "op", ">", "value", 150000, "tiers", List.of())))).value().getConditions().getFirst())
                .isEqualTo(new Condition("spend", ">", null, null, 150000.0));

        Function<Map<String, Object>, String> err = c -> validate(with(tierBase, "conditions", List.of(c))).errors().get("c0.tiers");
        assertThat(err.apply(tierCond("metric", "cpa"))).as("chỉ cho chi tiêu").isNotNull();
        assertThat(err.apply(tierCond("op", "<"))).as("chỉ cho lớn hơn").isNotNull();
        assertThat(err.apply(tierCond("tierMetric", "roas"))).as("loại kết quả").isNotNull();
        assertThat(err.apply(tierCond("tiers", List.of(map("count", 0, "value", 200000))))).as("từ 1").isNotNull();
        assertThat(err.apply(tierCond("tiers", List.of(map("count", 1.5, "value", 200000))))).as("số nguyên").isNotNull();
        assertThat(err.apply(tierCond("tiers", List.of(map("count", 2, "value", 120000))))).as("lớn hơn ngưỡng gốc").isNotNull();
        assertThat(err.apply(tierCond("tiers", List.of(map("count", 2, "value", 200000), map("count", 2, "value", 300000))))).as("số kết quả tăng dần").isNotNull();
        assertThat(err.apply(tierCond("tiers", List.of(map("count", 2, "value", 200000), map("count", 4, "value", 180000))))).as("ngưỡng tăng dần").isNotNull();
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 4; i++) many.add(map("count", i + 1, "value", 200000 + i * 10000));
        assertThat(err.apply(tierCond("tiers", many))).as("tối đa 3 bậc").isNotNull();
    }

    @Test
    void tiersEvaluate() {
        // id: chi tiêu, số lead, có khớp không — 0/1 lead giảm khi chi > 150k, từ 2 lead đợi tới > 200k
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("s140l0", new Object[]{140000, 0, false});
        cases.put("s160l0", new Object[]{160000, 0, true});
        cases.put("s160l1", new Object[]{160000, 1, true});
        cases.put("s160l2", new Object[]{160000, 2, false});
        cases.put("s199l3", new Object[]{199000, 3, false});
        cases.put("s210l2", new Object[]{210000, 2, true});
        List<AdObject> objs = new ArrayList<>();
        Map<String, Metrics> metrics = new HashMap<>();
        cases.forEach((id, c) -> {
            objs.add(obj(id, "adset", 500000, false));
            int leads = (int) c[1];
            metrics.put(id, m((int) c[0], leads, leads, 0));
        });
        Result<Rule> r = validate(with(tierBase, "minSpend", 0, "conditions", List.of(tierCond())));
        r.value().setId("r1");
        for (RuleEvaluator.Decision d : eval(r.value(), objs, x -> metrics)) {
            Object[] c = cases.get(d.obj().id());
            assertThat(d.hit()).as(d.obj().id()).isEqualTo(c[2]);
            assertThat(d.conds().getFirst().threshold()).as(d.obj().id()).isEqualTo((int) c[1] >= 2 ? 200000.0 : 150000.0);
            assertThat(d.conds().getFirst().tierCount()).isEqualTo(((Integer) c[1]).doubleValue());
        }
        Condition c2 = new Condition("spend", ">", null, null, null, 150000.0, "leads", List.of(new Condition.Tier(2, 200000), new Condition.Tier(4, 300000)));
        assertThat(List.of(0, 1, 2, 3, 4, 9).stream().map(n -> RuleEvaluator.spendTierOf(c2, n).value()).toList())
                .containsExactly(150000.0, 150000.0, 200000.0, 200000.0, 300000.0, 300000.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tiersRunLogsCount() {
        Rule saved = ruleService.save(mapper.convertValue(with(tierBase, "name", "Bậc", "enabled", true, "action", "notify", "range", "yesterday", "minSpend", 0,
                "cooldownHours", 1, "level", "campaign", "conditions", List.of(tierCond("value", 1, "tiers", List.of(map("count", 1000, "value", 2))))), RuleRequest.class)).item();
        created.add(saved.getId());
        runner.runRules();
        LogEntry l = logs.ofKind(LogKind.RULE).stream().filter(x -> saved.getId().equals(x.getRefId())).findFirst().orElseThrow();
        LogCondition.Hit c0 = l.getCondition().conditions().getFirst();
        assertThat(c0.tierMetric()).isEqualTo("leads");
        assertThat(c0.tierCount()).isNotNull();
        assertThat(l.getDetail()).containsPattern("đã có \\d+ Lead");
    }

    // ----- Tăng theo bậc kết quả

    static final List<Map<String, Object>> STEPS = List.of(map("count", 1, "mode", "percent", "value", 50), map("count", 2, "mode", "percent", "value", 50),
            map("count", 3, "mode", "percent", "value", 50, "everyHours", 3));
    final Map<String, Object> ladderIn = map("name", "Bậc", "action", "ladder", "ladderMetric", "results", "steps", STEPS, "minSpend", 100000,
            "maxBudget", 2000000, "allActive", true, "level", "adset");

    Rule ladder() {
        Result<Rule> r = validate(ladderIn);
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        r.value().setId("rl");
        return r.value();
    }

    RuleEvaluator.Decision ev(Rule r, AdObject o, double spend, double results) {
        return eval(r, List.of(o), x -> Map.of(o.id(), m(spend, results))).getFirst();
    }

    /** Như runRules sau khi tăng thành công: ghi bậc đã chạy, cập nhật ngân sách */
    void did(RuleEvaluator.Decision d, long agoMs) {
        state.setLadder("rl", d.obj().id(), clock.now().date(), d.ladder().step(), clock.millis() - agoMs);
        d.obj().applyBudget(d.plan().next());
    }

    @Test
    void ladderValidation() {
        Result<Rule> r = validate(ladderIn);
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        assertThat(r.value().getConditions()).isEmpty();
        assertThat(r.value().getRange()).isEqualTo(RuleRange.TODAY);
        assertThat(r.value().getCooldownHours()).isZero();
        assertThat(r.value().getIncludeLearning()).isTrue();
        assertThat(r.value().getSteps()).containsExactly(new Rule.Step(1, "percent", 50, null), new Rule.Step(2, "percent", 50, null), new Rule.Step(3, "percent", 50, 3.0));
        assertThat(validate(with(ladderIn, "maxBudget", "")).errors()).containsKey("maxBudget");

        Function<List<Map<String, Object>>, String> bad = st -> validate(with(ladderIn, "steps", st)).errors().get("steps");
        assertThat(bad.apply(List.of())).as("ít nhất 1 bậc").isNotNull();
        assertThat(bad.apply(List.of(map("count", 2, "value", 50), map("count", 2, "value", 50)))).as("tăng dần").isNotNull();
        assertThat(bad.apply(List.of(map("count", 0, "value", 50)))).as("từ 1").isNotNull();
        assertThat(bad.apply(List.of(map("count", 1, "value", 0)))).as("mức tăng > 0").isNotNull();
        assertThat(bad.apply(List.of(map("count", 1, "value", 150)))).as("tối đa 100%").isNotNull();
        assertThat(bad.apply(List.of(map("count", 1, "value", 50, "everyHours", 0.5)))).as("lặp lại từ 1 giờ").isNotNull();
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) many.add(map("count", i + 1, "value", 10));
        assertThat(bad.apply(many)).as("tối đa 5 bậc").isNotNull();
        // chỉ bậc cuối được lặp lại
        assertThat(validate(with(ladderIn, "steps", List.of(map("count", 1, "value", 10, "everyHours", 3), map("count", 2, "value", 10)))).value().getSteps().getFirst().everyHours()).isNull();
    }

    @Test
    void ladderNotReached() {
        assertThat(ev(ladder(), obj("a1", "adset", 200000, true), 90000, 1).status()).isEqualTo(DecisionStatus.SKIP);
        RuleEvaluator.Decision d = ev(ladder(), obj("a1", "adset", 200000, true), 120000, 0);
        assertThat(d.hit()).isFalse();
        assertThat(d.reason()).isEqualTo("Chưa có 1 kết quả (đang có 0)");
    }

    @Test
    void ladderWalksSteps() {
        AdObject o = obj("a1", "adset", 200000, true);
        Rule r = ladder();
        RuleEvaluator.Decision d = ev(r, o, 120000, 1);
        assertThat(d.status()).isEqualTo(DecisionStatus.MATCH);
        assertThat(d.plan().next()).isEqualTo(300000);
        did(d, 0);
        d = ev(r, o, 130000, 1);
        assertThat(d.status()).isEqualTo(DecisionStatus.SKIP);
        assertThat(d.code()).isEqualTo("ladderdone");
        assertThat(d.reason()).isEqualTo("Hôm nay đã tăng bậc 1, chờ có 2 kết quả để lên bậc 2");
        d = ev(r, o, 150000, 2);
        assertThat(d.status()).isEqualTo(DecisionStatus.MATCH);
        assertThat(d.plan().next()).as("không bị giới hạn 30%/ngày, kể cả đang học").isEqualTo(450000);
        did(d, 0);
        d = ev(r, o, 180000, 3);
        assertThat(d.status()).isEqualTo(DecisionStatus.MATCH);
        assertThat(d.plan().next()).isEqualTo(675000);
        did(d, 0);
        d = ev(r, o, 200000, 3);
        assertThat(d.code()).as("chưa đủ 3 giờ").isEqualTo("ladderwait");
        state.setLadder("rl", "a1", clock.now().date(), 2, clock.millis() - 3 * 3_600_000L - 1000);
        d = ev(r, o, 250000, 4);
        assertThat(d.status()).isEqualTo(DecisionStatus.MATCH);
        assertThat(d.plan().next()).isEqualTo(1012500);
    }

    @Test
    void ladderJumpsAndCaps() {
        AdObject o = obj("a1", "adset", 1500000, true);
        RuleEvaluator.Decision d = ev(ladder(), o, 120000, 3);
        assertThat(d.ladder().step()).isEqualTo(2);
        assertThat(d.plan().next()).as("chạm trần 2.000.000").isEqualTo(2000000);
        did(d, 0);
        assertThat(ev(ladder(), o, 120000, 3).code()).isEqualTo("ladderwait");
        // ngày mới: bậc tính lại từ đầu
        clock.setClock(Clock.fixed(Instant.parse("2031-03-05T13:00:00Z"), ZoneOffset.UTC));
        assertThat(ev(ladder(), obj("a1", "adset", 200000, true), 120000, 3).status()).isEqualTo(DecisionStatus.MATCH);
    }

    @Test
    void ladderSkipsLearningWhenOff() {
        Rule r = ladder();
        r.setIncludeLearning(false);
        RuleEvaluator.Decision d = ev(r, obj("a1", "adset", 200000, true), 120000, 1);
        assertThat(d.status()).isEqualTo(DecisionStatus.SKIP);
        assertThat(d.code()).isEqualTo("learning");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ladderRunLogsStepOnce() {
        Rule saved = ruleService.save(mapper.convertValue(with(ladderIn, "enabled", true, "level", "campaign", "minSpend", 0, "maxBudget", 50000000), RuleRequest.class)).item();
        created.add(saved.getId());
        // bậc 0 kết quả: mọi camp đang chạy đều đạt bậc 1, không phụ thuộc giờ chạy test (số giả tăng theo giờ trong ngày)
        saved.setSteps(List.of(new Rule.Step(0, "percent", 10, null)));
        ruleRepo.save(saved);
        runner.runRules();
        List<LogEntry> l = logs.ofKind(LogKind.RULE).stream().filter(x -> saved.getId().equals(x.getRefId()) && !Boolean.TRUE.equals(x.getSkipped())).toList();
        assertThat(l).as("có camp mock được tăng").isNotEmpty();
        assertThat(l.getFirst().getCondition().ladder().step()).isEqualTo(1);
        assertThat(l.getFirst().getSource()).containsPattern("Bậc 1: có \\d+ kết quả");
        runner.runRules();
        assertThat(logs.ofKind(LogKind.RULE).stream().filter(x -> saved.getId().equals(x.getRefId()) && !Boolean.TRUE.equals(x.getSkipped())).count())
                .as("không tăng lại bậc đã chạy hôm nay").isEqualTo(l.size());
    }
}
