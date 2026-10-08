package com.fbads.rule;

import com.fbads.ads.AdLevel;
import com.fbads.ads.AdObject;
import com.fbads.common.Checks;
import com.fbads.common.Fmt;
import com.fbads.common.Json;
import com.fbads.common.Result;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kiểm tra rule trước khi lưu / xem trước (bản Java của validateRule trong shared/validate.mjs).
 * Lỗi của điều kiện thứ i nằm ở khoá "c{i}.{trường}"; điều kiện đầu còn ghi ra khoá cũ (metric/op/value).
 */
public final class RuleValidator {
    static final List<String> METRICS = List.of("cpa", "roas", "spend", "results", "ctr", "cpc", "cpm", "messages",
            "costPerMessage", "leads", "costPerLead", "frequency");
    static final List<String> COST_METRICS = List.of("cpa", "spend", "cpc", "cpm", "costPerMessage", "costPerLead");
    static final List<String> TARGET_METRICS = List.of("cpa", "roas", "spend");
    static final int MAX_CONDITIONS = 5;
    /** Các số liệu dạng tổng: so hai khoảng khác độ dài thì chia trung bình theo ngày (số ngày: RuleRange.days) */
    public static final List<String> TOTAL_METRICS = List.of("spend", "results", "messages", "leads");
    /** Ngưỡng chi tiêu nâng theo số kết quả: loại kết quả, tối đa 3 bậc */
    static final List<String> TIER_METRICS = List.of("leads", "results", "messages");
    static final int MAX_TIERS = 3;
    /** Rule tăng theo bậc kết quả: loại kết quả, tối đa 5 bậc */
    static final List<String> LADDER_METRICS = List.of("results", "leads", "messages");
    static final int MAX_STEPS = 5;
    static final Map<String, String> METRIC_LABEL = Map.ofEntries(
            Map.entry("cpa", "CPA"), Map.entry("roas", "ROAS"), Map.entry("spend", "Chi tiêu"), Map.entry("results", "Số kết quả"),
            Map.entry("ctr", "CTR"), Map.entry("cpc", "CPC"), Map.entry("cpm", "CPM"), Map.entry("messages", "Số tin nhắn"),
            Map.entry("costPerMessage", "Chi phí/tin nhắn"), Map.entry("leads", "Số lead"), Map.entry("costPerLead", "Chi phí/lead"),
            Map.entry("frequency", "Tần suất"));

    /** Tài khoản đang quản lý (id, tên) — để cảnh báo phạm vi/mục tiêu */
    public record Account(String id, String name) {}

    private RuleValidator() {}

    /** Lỗi của điều kiện thứ i: khoá "c{i}.{trường}"; điều kiện đầu còn ghi ra khoá cũ (metric/op/value) */
    private record CondErrors(Map<String, String> e, int i) {
        void put(String field, String msg) {
            e.put("c" + i + "." + field, msg);
            if (i == 0) e.put(field, msg);
        }
    }

    /** Lỗi của bậc thứ i (rule tăng theo bậc): khoá "s{i}" và khoá chung "steps", chỉ giữ lỗi đầu tiên */
    private record StepErrors(Map<String, String> e, int i) {
        void put(String msg) {
            e.putIfAbsent("s" + i, msg);
            e.putIfAbsent("steps", msg);
        }
    }

    /** Rule tăng chi tiêu (tăng ngân sách) hay giảm chi (tắt, giảm ngân sách): hai rule ngược chiều thì có thể mâu thuẫn */
    private static boolean raisesSpend(RuleAction a) { return a == RuleAction.INCREASE; }

    static String targetKey(String metric) { return "spend".equals(metric) ? "cpa" : metric; }

    private static <T> List<T> uniq(List<T> a) { return new ArrayList<>(new LinkedHashSet<>(a)); }

    private static boolean condOverlap(String aOp, double aVal, Rule b) {
        if (Objects.equals(aOp, b.getOp())) return true;
        double lo, hi;
        if (">".equals(aOp)) { lo = aVal; hi = b.getValue(); } else { lo = b.getValue(); hi = aVal; }
        return lo < hi;
    }

    /**
     * Ngưỡng của điều kiện "so với khoảng khác" từ giá trị ở khoảng so sánh (như compareThreshold của validate.mjs);
     * null = chưa có số liệu để so
     */
    public static Double compareThreshold(Condition c, Double base) {
        if (base == null || !Double.isFinite(base) || base <= 0) return null;
        double perDay = TOTAL_METRICS.contains(c.metric()) ? base / RuleRange.daysOf(c.compareRange()) : base;
        double f = c.factor() == null || c.factor() == 0 || c.factor().isNaN() ? 100 : c.factor();
        return perDay * f / 100;
    }

    private static boolean isInt(double n) { return Double.isFinite(n) && n == Math.rint(n); }

    /** Ngưỡng chi tiêu nâng theo số kết quả (chỉ cho "Chi tiêu lớn hơn" số cụ thể); không có bậc → null */
    private static Condition withTiers(RuleRequest.ConditionRequest cn, String metric, String op, double value, CondErrors errs) {
        if (cn.tiers() == null || cn.tiers().isEmpty()) return null;
        if (!"spend".equals(metric) || !">".equals(op)) {
            errs.put("tiers", "Nâng ngưỡng theo kết quả chỉ dùng cho điều kiện \"Chi tiêu lớn hơn\"");
            return null;
        }
        String tierMetric = TIER_METRICS.contains(Json.str(cn.tierMetric())) ? cn.tierMetric() : null;
        if (tierMetric == null) errs.put("tiers", "Chọn loại kết quả để nâng ngưỡng");
        if (cn.tiers().size() > MAX_TIERS) errs.put("tiers", "Tối đa " + MAX_TIERS + " bậc");
        List<double[]> tiers = new ArrayList<>();
        for (RuleRequest.TierRequest t : cn.tiers().subList(0, Math.min(MAX_TIERS, cn.tiers().size())))
            tiers.add(new double[]{Json.num(t == null ? null : t.count()), Json.num(t == null ? null : t.value())});
        double prevCount = 0, prevValue = Double.isFinite(value) ? value : 0;
        for (double[] t : tiers) {
            if (!isInt(t[0]) || t[0] < 1) { errs.put("tiers", "Số kết quả của mỗi bậc là số nguyên từ 1 trở lên"); break; }
            if (!Double.isFinite(t[1]) || t[1] <= 0) { errs.put("tiers", "Nhập ngưỡng chi tiêu cho mỗi bậc"); break; }
            if (t[0] <= prevCount) { errs.put("tiers", "Số kết quả của bậc sau phải lớn hơn bậc trước"); break; }
            if (t[1] <= prevValue) { errs.put("tiers", "Ngưỡng của bậc sau phải lớn hơn ngưỡng trước đó"); break; }
            prevCount = t[0];
            prevValue = t[1];
        }
        return new Condition(metric, op, null, null, null, Double.isFinite(value) ? value : 0.0, tierMetric != null ? tierMetric : "leads",
                tiers.stream().map(t -> new Condition.Tier(Double.isFinite(t[0]) ? t[0] : 0, Double.isFinite(t[1]) ? t[1] : 0)).toList());
    }

    /** Các bậc của rule tăng theo bậc kết quả (như ladderOf của validate.mjs) */
    private static List<Rule.Step> stepsOf(RuleRequest input, Map<String, String> e) {
        List<RuleRequest.StepRequest> raw = input.steps() == null ? List.of() : input.steps();
        if (raw.isEmpty()) e.put("steps", "Thêm ít nhất 1 bậc");
        else if (raw.size() > MAX_STEPS) e.put("steps", "Tối đa " + MAX_STEPS + " bậc");
        List<RuleRequest.StepRequest> all = raw.subList(0, Math.min(MAX_STEPS, raw.size()));
        List<Rule.Step> steps = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            RuleRequest.StepRequest t = all.get(i) == null ? new RuleRequest.StepRequest(null, null, null, null) : all.get(i);
            double count = Json.num(t.count()), value = Json.num(t.value());
            String mode = "amount".equals(t.mode()) ? "amount" : "percent";
            boolean last = i == all.size() - 1;
            double every = last && t.everyHours() != null && t.everyHours() != 0 ? t.everyHours() : 0;
            StepErrors errs = new StepErrors(e, i);
            if (!isInt(count) || count < 1) errs.put("Bậc " + (i + 1) + ": số kết quả là số nguyên từ 1 trở lên");
            else if (i > 0 && all.get(i - 1) != null && count <= Json.num(all.get(i - 1).count()))
                errs.put("Bậc " + (i + 1) + ": số kết quả phải lớn hơn bậc trước");
            if (!Double.isFinite(value) || value <= 0) errs.put("Bậc " + (i + 1) + ": nhập mức tăng lớn hơn 0");
            else if (mode.equals("percent") && value > Checks.RULE_PCT_INCREASE_MAX)
                errs.put("Bậc " + (i + 1) + ": tăng tối đa " + Checks.RULE_PCT_INCREASE_MAX + "% mỗi lần");
            else if (mode.equals("amount") && value > Checks.BUDGET_MAX)
                errs.put("Bậc " + (i + 1) + ": số tiền quá lớn, hãy kiểm tra lại số 0");
            if (!Double.isFinite(every) || every < 0 || (every > 0 && (every < 1 || every > 24)))
                errs.put("Bậc " + (i + 1) + ": lặp lại mỗi 1 đến 24 giờ");
            steps.add(new Rule.Step(Double.isFinite(count) ? count : 0, mode,
                    Double.isFinite(value) ? (mode.equals("amount") ? Math.round(value) : value) : 0,
                    every > 0 && Double.isFinite(every) ? every : null));
        }
        return steps;
    }

    public static Result<Rule> validate(RuleRequest in, List<AdObject> objs, List<Rule> rules,
            Map<String, Map<String, Number>> accountTargets, List<Account> accounts) {
        Result.Collector c = new Result.Collector();
        Map<String, String> e = c.errors();
        RuleRequest input = in == null ? RuleRequest.EMPTY : in;
        String name = Json.str(input.name()).trim();
        if (name.length() > Checks.NAME_MAX) e.put("name", "Tên tối đa " + Checks.NAME_MAX + " ký tự");

        // ----- Điều kiện (1..5), gộp bằng VÀ / HOẶC. Rule "tăng theo bậc kết quả" không có điều kiện: bậc nào đạt thì tăng theo bậc đó.
        RuleAction action = RuleAction.from(input.action());
        boolean isLadder = action == RuleAction.LADDER;
        List<RuleRequest.ConditionRequest> rawConds = isLadder ? List.of()
                : input.conditions() != null && !input.conditions().isEmpty() ? input.conditions()
                : List.of(new RuleRequest.ConditionRequest(input.metric(), input.op(), null, null, input.value()));
        if (rawConds.size() > MAX_CONDITIONS) e.put("conditions", "Tối đa " + MAX_CONDITIONS + " điều kiện cho mỗi rule");
        List<Condition> conds = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_CONDITIONS, rawConds.size()); i++) {
            RuleRequest.ConditionRequest cn = rawConds.get(i) == null ? RuleRequest.ConditionRequest.EMPTY : rawConds.get(i);
            CondErrors errs = new CondErrors(e, i);
            String metric = cn.metric();
            if (!METRICS.contains(Json.str(metric))) errs.put("metric", "Số liệu không hợp lệ");
            String opRaw = Json.str(cn.op());
            String op = "<".equals(opRaw) ? "<" : ">".equals(opRaw) ? ">" : null;
            if (op == null) errs.put("op", "Phép so sánh không hợp lệ");
            if ("target".equals(cn.vs())) { // so với mục tiêu của từng tài khoản: ngưỡng = mục tiêu × factor%
                if (!TARGET_METRICS.contains(Json.str(metric))) errs.put("metric", "Chỉ CPA, ROAS và Chi tiêu so được với mục tiêu");
                double factor = cn.factor() == null ? 100 : cn.factor();
                if (!Double.isFinite(factor) || factor <= 0 || factor > 1000)
                    errs.put("value", "Phần trăm so với mục tiêu phải từ 1 đến 1000");
                conds.add(new Condition(metric, op, "target", Double.isFinite(factor) ? factor : 100, 0.0));
                continue;
            }
            if ("range".equals(cn.vs())) { // so với chính số liệu đó ở khoảng khác: ngưỡng = giá trị ở khoảng so sánh × factor%
                String compareRange = RuleRange.from(cn.compareRange()) != null ? cn.compareRange() : null;
                if (compareRange == null) errs.put("value", "Chọn khoảng thời gian để so sánh");
                else if (compareRange.equals(Json.truthy(input.range()) ? input.range() : "today"))
                    errs.put("value", "Khoảng so sánh phải khác khoảng tính số liệu của rule");
                double factor = cn.factor() == null ? 100 : cn.factor();
                if (!Double.isFinite(factor) || factor <= 0 || factor > 1000)
                    errs.put("value", "Phần trăm so với khoảng khác phải từ 1 đến 1000");
                conds.add(new Condition(metric, op, "range", compareRange != null ? compareRange : "last_7d",
                        Double.isFinite(factor) ? factor : 100, 0.0, null, null));
                continue;
            }
            double value = Json.num(cn.value());
            if (!Double.isFinite(value)) errs.put("value", "Nhập ngưỡng so sánh");
            else if (value < 0) errs.put("value", "Ngưỡng không được âm");
            else if (COST_METRICS.contains(Json.str(metric)) && ">".equals(op) && value <= 0)
                errs.put("value", "Ngưỡng " + METRIC_LABEL.get(metric) + " phải lớn hơn 0, nếu không rule sẽ khớp với mọi camp.");
            else if ("roas".equals(metric) && value > 100) errs.put("value", "ROAS lớn hơn 100 là bất thường, hãy kiểm tra lại");
            else if ("ctr".equals(metric) && value > 100) errs.put("value", "CTR là phần trăm, tối đa 100");
            else if ("frequency".equals(metric) && value > 50) errs.put("value", "Tần suất lớn hơn 50 là bất thường, hãy kiểm tra lại");
            Condition tiered = withTiers(cn, metric, op, value, errs);
            conds.add(tiered != null ? tiered : new Condition(metric, op, null, null, Double.isFinite(value) ? value : 0.0));
        }
        Condition first = conds.isEmpty() ? new Condition(null, null, null, null, 0.0) : conds.getFirst();
        String metric = first.metric(), op = first.op();
        double value = first.value() == null ? 0 : first.value();
        MatchMode match = "any".equals(input.match()) ? MatchMode.ANY : MatchMode.ALL;
        // Điều kiện tự mâu thuẫn (VÀ): cùng số liệu vừa lớn hơn a vừa nhỏ hơn b mà a ≥ b thì không bao giờ khớp
        if (match == MatchMode.ALL && e.isEmpty()) {
            for (String m : uniq(conds.stream().filter(x -> x.vs() == null).map(Condition::metric).toList())) {
                List<Condition> same = conds.stream().filter(x -> Objects.equals(x.metric(), m) && x.vs() == null).toList();
                double lo = same.stream().filter(x -> ">".equals(x.op()))
                        .mapToDouble(Condition::value).max().orElse(Double.NEGATIVE_INFINITY);
                double hi = same.stream().filter(x -> "<".equals(x.op()))
                        .mapToDouble(Condition::value).min().orElse(Double.POSITIVE_INFINITY);
                if (lo >= hi) {
                    c.warn("Các điều kiện về " + METRIC_LABEL.get(m) + " mâu thuẫn nhau (vừa lớn hơn " + Fmt.num(lo)
                            + ", vừa nhỏ hơn " + Fmt.num(hi) + ") nên rule sẽ không bao giờ khớp.");
                    break;
                }
            }
        }

        RuleRange range = isLadder || !Json.truthy(input.range()) ? RuleRange.TODAY : RuleRange.from(input.range());
        if (range == null) e.put("range", "Khoảng thời gian không hợp lệ");

        boolean hasDataMetric = conds.stream().anyMatch(x -> x.metric() != null && !x.metric().equals("spend"));
        double minSpend = input.minSpend() == null ? 0 : input.minSpend();
        if (!Double.isFinite(minSpend) || minSpend < 0) e.put("minSpend", "Chi tiêu tối thiểu phải là số không âm");
        else if (hasDataMetric && minSpend <= 0)
            e.put("minSpend", "Cần đặt chi tiêu tối thiểu lớn hơn 0 để không quyết định khi camp mới chạy, chưa đủ dữ liệu.");

        if (action == null) e.put("action", "Hành động không hợp lệ");
        String ladderMetric = null;
        List<Rule.Step> steps = null;
        if (isLadder) {
            ladderMetric = LADDER_METRICS.contains(Json.str(input.ladderMetric())) ? input.ladderMetric() : null;
            if (ladderMetric == null) e.put("ladderMetric", "Chọn loại kết quả để tính bậc");
            steps = stepsOf(input, e);
            if (ladderMetric == null) ladderMetric = "results";
        }
        if (range == RuleRange.TODAY && conds.stream().anyMatch(x -> x.vsRange() && TOTAL_METRICS.contains(x.metric())))
            c.warn("Số liệu hôm nay mới tính đến giờ hiện tại, còn khoảng so sánh là trung bình cả ngày, nên chi "
                    + "tiêu/số kết quả hôm nay thường thấp hơn vào buổi sáng. Nên so CPA, ROAS hoặc CTR, hoặc dùng khung giờ cuối ngày.");
        if (range == RuleRange.TODAY && hasDataMetric && (action == RuleAction.PAUSE || action == RuleAction.DECREASE))
            c.warn("Rule đang chỉ dựa trên số liệu hôm nay. Chuyển đổi thường về trễ nên dễ tắt/giảm oan; nên dùng “3 "
                    + "ngày gần nhất” hoặc dài hơn.");
        boolean budgetAction = action == RuleAction.INCREASE || action == RuleAction.DECREASE;
        String budgetMode = budgetAction && "amount".equals(input.budgetMode()) ? "amount" : "percent";
        double amount = Json.num(input.amount());
        if (budgetMode.equals("amount")) {
            if (!Double.isFinite(amount) || amount <= 0) e.put("amount", "Nhập số tiền thay đổi lớn hơn 0");
            else if (amount > Checks.BUDGET_MAX) e.put("amount", "Số tiền quá lớn, hãy kiểm tra lại số 0");
            else amount = Math.round(amount);
        } else amount = 0;
        double pct = Json.num(input.pct());
        if (budgetMode.equals("amount")) pct = 0;
        else if (budgetAction) {
            if (!Double.isFinite(pct) || pct <= 0) e.put("pct", "Nhập % thay đổi lớn hơn 0");
            else if (action == RuleAction.DECREASE && pct > Checks.RULE_PCT_DECREASE_MAX)
                e.put("pct", "Giảm tối đa " + Checks.RULE_PCT_DECREASE_MAX + "% mỗi lần (giảm 100% là đưa ngân sách về 0)");
            else if (action == RuleAction.INCREASE && pct > Checks.RULE_PCT_INCREASE_MAX)
                e.put("pct", "Tăng tối đa " + Checks.RULE_PCT_INCREASE_MAX + "% mỗi lần");
            else if (pct > Checks.BIG_PCT_WARN)
                c.warn((action == RuleAction.INCREASE ? "Tăng" : "Giảm") + " " + Fmt.num(pct)
                        + "% mỗi lần là khá lớn, Facebook có thể học lại từ đầu. Nên khoảng 20%.");
        } else pct = 0;

        double maxBudget = input.maxBudget() == null ? 0 : input.maxBudget();
        double minBudget = input.minBudget() == null ? 0 : input.minBudget();
        if (!Double.isFinite(maxBudget) || maxBudget < 0) e.put("maxBudget", "Trần ngân sách phải là số không âm");
        if (!Double.isFinite(minBudget) || minBudget < 0) e.put("minBudget", "Sàn ngân sách phải là số không âm");
        if (!e.containsKey("maxBudget") && !e.containsKey("minBudget") && maxBudget > 0 && minBudget > 0 && maxBudget < minBudget)
            e.put("maxBudget", "Trần ngân sách phải lớn hơn hoặc bằng sàn");
        if (isLadder && !e.containsKey("maxBudget") && maxBudget <= 0)
            e.put("maxBudget", "Rule tăng theo bậc cần đặt trần ngân sách (không bị giới hạn % mỗi ngày nên phải có trần)");
        if (action == RuleAction.INCREASE && !e.containsKey("maxBudget") && maxBudget <= 0)
            c.warn("Chưa đặt trần ngân sách: ngân sách có thể tăng mãi qua nhiều ngày. Nên đặt trần.");
        if (action == RuleAction.DECREASE && !e.containsKey("minBudget") && minBudget <= 0)
            c.warn("Chưa đặt sàn ngân sách: ngân sách có thể giảm rất thấp qua nhiều lần. Nên đặt sàn.");

        // bậc tự giữ nhịp, không dùng thời gian nghỉ
        double cooldown = isLadder || input.cooldownHours() == null ? 0 : input.cooldownHours();
        if (!Double.isFinite(cooldown) || cooldown < 0 || cooldown > Checks.COOLDOWN_MAX)
            e.put("cooldownHours", "Thời gian nghỉ từ 0 đến " + Checks.COOLDOWN_MAX + " giờ");
        else if (budgetAction && cooldown < 1)
            e.put("cooldownHours", "Rule đổi ngân sách cần nghỉ ít nhất 1 giờ giữa hai lần, nếu không ngân sách sẽ thay "
                    + "đổi liên tục mỗi lần kiểm tra.");
        else if (action == RuleAction.NOTIFY && cooldown < 1)
            e.put("cooldownHours", "Rule chỉ thông báo cần nghỉ ít nhất 1 giờ giữa hai lần, nếu không bạn sẽ nhận thông "
                    + "báo lặp lại mỗi lần kiểm tra.");

        // Tự bật lại (chỉ với rule tắt): '' = không, 'nextday' = bật lại lúc resumeAt của ngày hôm sau
        String resume = action == RuleAction.PAUSE && "nextday".equals(input.resume()) ? "nextday" : "";
        String resumeAt = !Json.truthy(input.resumeAt()) ? "06:00" : input.resumeAt();
        if (!resume.isEmpty() && !Checks.isTime(resumeAt)) e.put("resumeAt", "Giờ bật lại không hợp lệ (dạng HH:MM, ví dụ 06:00)");
        if (!resume.isEmpty() && range != null && range != RuleRange.TODAY && hasDataMetric)
            c.warn("Rule tự bật lại nhưng số liệu tính theo “" + range.label() + "” vẫn gồm những ngày xấu, nên "
                    + ("adset".equals(input.level()) ? "nhóm QC" : "camp")
                    + " có thể bị tắt lại ngay sau khi bật. Kiểu “tắt hôm nay, mai chạy lại” nên dùng số liệu “hôm nay”.");

        String from = Json.str(input.from()), to = Json.str(input.to());
        if (!from.isEmpty() || !to.isEmpty()) {
            if (from.isEmpty() || to.isEmpty()) e.put("window", "Hãy nhập cả giờ bắt đầu và giờ kết thúc (hoặc để trống cả hai)");
            else if (!Checks.isTime(from) || !Checks.isTime(to)) e.put("window", "Khung giờ không hợp lệ");
            else if (from.compareTo(to) >= 0) e.put("window", "Giờ bắt đầu phải nhỏ hơn giờ kết thúc (chưa hỗ trợ khung giờ qua đêm)");
        }

        // Cấp áp dụng: chiến dịch (mặc định) hoặc nhóm QC
        AdLevel level = "adset".equals(input.level()) ? AdLevel.ADSET : AdLevel.CAMPAIGN;
        String unit = level == AdLevel.ADSET ? "nhóm QC" : "camp";
        boolean allActive = !Boolean.FALSE.equals(input.allActive());
        List<String> targets = uniq(Json.strings(input.targets()));
        if (!allActive) {
            if (targets.isEmpty()) e.put("targets", "Hãy chọn ít nhất 1 " + unit + " áp dụng");
            else if (objs != null) {
                List<String> unknown = targets.stream().filter(id -> objs.stream().noneMatch(o -> o.id().equals(id))).toList();
                List<String> other = targets.stream()
                        .filter(id -> objs.stream().anyMatch(o -> o.id().equals(id) && o.level() != null && o.level() != level))
                        .toList();
                if (!unknown.isEmpty())
                    e.put("targets", "Có mục không còn tồn tại trên tài khoản: "
                            + String.join(", ", unknown.stream().limit(3).toList()) + ".");
                else if (!other.isEmpty())
                    e.put("targets", "Có " + other.size() + " mục không phải "
                            + (level == AdLevel.ADSET ? "nhóm QC" : "chiến dịch") + ", hãy chọn lại.");
            }
        }

        // Phạm vi tài khoản (chỉ khi áp dụng cho "tất cả camp đang chạy"): trống = mọi tài khoản đang quản lý
        List<String> accountIds = allActive
                ? uniq(Json.strings(input.accountIds()).stream().map(String::trim).filter(s -> !s.isEmpty()).toList())
                : List.of();
        if (accountIds.size() > Checks.ACCOUNTS_MAX) e.put("accountIds", "Tối đa " + Checks.ACCOUNTS_MAX + " tài khoản");
        if (accounts != null && !accountIds.isEmpty()) {
            List<String> gone = accountIds.stream().filter(id -> accounts.stream().noneMatch(a -> a.id().equals(id))).toList();
            if (!gone.isEmpty()) c.warn("Rule đang giới hạn theo tài khoản " + String.join(", ", gone.stream().limit(3).toList())
                    + " nhưng tài khoản này không còn được quản lý, nên không " + unit + " nào được xét.");
        }

        // Rule đổi ngân sách: mục không có ngân sách riêng (CBO/ABO) bị bỏ qua
        if (budgetAction && objs != null && !e.containsKey("targets")) {
            List<AdObject> pool = allActive
                    ? objs.stream().filter(o -> o.level() == level && o.isActive()
                            && (accountIds.isEmpty() || accountIds.contains(o.accountId()))).toList()
                    : objs.stream().filter(o -> targets.contains(o.id())).toList();
            long none = pool.stream().filter(o -> o.dailyBudget() == null).count();
            String why = level == AdLevel.ADSET ? "nằm trong chiến dịch CBO (ngân sách đặt ở chiến dịch)"
                    : "là chiến dịch ABO (ngân sách đặt ở từng nhóm QC)";
            if (!pool.isEmpty() && none == pool.size())
                c.warn("Không " + unit + " nào " + (allActive ? "đang chạy " : "đã chọn ") + "có ngân sách riêng (đều " + why
                        + "), nên rule này sẽ không đổi được ngân sách. "
                        + (level == AdLevel.ADSET ? "Hãy dùng rule cấp chiến dịch." : "Hãy dùng rule cấp nhóm QC."));
            else if (none > 0) c.warn(none + " " + unit + " không có ngân sách riêng (" + why + ") sẽ bị bỏ qua.");
        }
        // So với mục tiêu: mỗi tài khoản trong phạm vi phải có mục tiêu tương ứng
        List<Condition> tcs = conds.stream().filter(Condition::vsTarget).toList();
        if (!tcs.isEmpty() && accountTargets != null && accounts != null) {
            List<String> inScope = !accountIds.isEmpty() ? accountIds
                    : allActive ? accounts.stream().map(Account::id).toList()
                    : uniq(targets.stream().map(id -> (objs == null ? List.<AdObject>of()
                            : objs).stream().filter(o -> o.id().equals(id)).map(o -> o.accountId()).findFirst().orElse(null))
                    .filter(Objects::nonNull).toList());
            for (String id : inScope) {
                Map<String, Number> t = accountTargets.getOrDefault(id, Map.of());
                List<String> missing = uniq(tcs.stream().map(x -> targetKey(x.metric())).toList()).stream()
                        .filter(m -> !(t.get(m) != null && t.get(m).doubleValue() > 0)).toList();
                if (missing.isEmpty()) continue;
                String nm = accounts.stream().filter(a -> a.id().equals(id)).map(Account::name).findFirst().orElse(id);
                c.warn("Tài khoản “" + nm + "” chưa đặt mục tiêu " + String.join(", ", missing.stream().map(METRIC_LABEL::get).toList())
                        + ": rule sẽ bỏ qua camp của tài khoản này (đặt ở Cài đặt → Mục tiêu).");
            }
        }

        // Cảnh báo mâu thuẫn với rule khác đang bật (chỉ so được khi cả hai rule chỉ có 1 điều kiện số cụ thể)
        boolean enabled = !Boolean.FALSE.equals(input.enabled());
        String inputId = Json.truthy(input.id()) ? input.id() : null;
        if (enabled && !e.containsKey("metric") && !e.containsKey("op") && !e.containsKey("value") && !e.containsKey("action")
                && conds.size() == 1 && conds.getFirst().vs() == null) {
            for (Rule o : rules) {
                if (o.getId() != null && o.getId().equals(inputId)) continue;
                boolean simple = o.conditionList().size() == 1 && o.conditionList().getFirst().vs() == null;
                if (!o.isEnabled() || !simple || !Objects.equals(o.getMetric(), metric)
                        || !(">".equals(o.getOp()) || "<".equals(o.getOp()))) continue;
                if (o.getAction() == RuleAction.NOTIFY || action == RuleAction.NOTIFY) continue;
                if ((o.getLevel() == null ? AdLevel.CAMPAIGN : o.getLevel()) != level) continue;
                boolean scopeOverlap = allActive || o.isAllActive()
                        || targets.stream().anyMatch(t -> o.getTargets() != null && o.getTargets().contains(t));
                if (!scopeOverlap) continue;
                if (raisesSpend(o.getAction()) != raisesSpend(action) && condOverlap(op, value, o)) {
                    c.warn("Rule “" + o.getName() + "” có thể mâu thuẫn: cùng xét " + METRIC_LABEL.get(metric) + " nhưng "
                            + (o.getAction() == RuleAction.INCREASE ? "tăng" : o.getAction() == RuleAction.PAUSE ? "tắt" : "giảm")
                                    + " ngân sách trong vùng giá trị chồng lấn.");
                    break;
                }
            }
        }

        Rule r = new Rule();
        r.setId(inputId);
        r.setName(name.isEmpty() ? "Rule mới" : name);
        r.setMetric(isLadder ? "" : metric);
        r.setOp(isLadder ? "" : op);
        r.setValue(value);
        r.setConditions(conds);
        r.setMatch(match);
        r.setRange(range == null ? RuleRange.TODAY : range);
        r.setMinSpend(Double.isFinite(minSpend) ? minSpend : 0);
        r.setAction(action);
        r.setPct(Double.isFinite(pct) ? pct : 0);
        r.setBudgetMode(budgetMode);
        r.setAmount(Double.isFinite(amount) ? amount : 0);
        r.setMaxBudget(Double.isFinite(maxBudget) ? maxBudget : 0);
        r.setMinBudget(Double.isFinite(minBudget) ? minBudget : 0);
        r.setCooldownHours(Double.isFinite(cooldown) ? cooldown : 0);
        r.setResume(resume);
        r.setResumeAt(resume.isEmpty() ? "" : resumeAt);
        r.setFrom(!from.isEmpty() && !to.isEmpty() ? from : "");
        r.setTo(!from.isEmpty() && !to.isEmpty() ? to : "");
        r.setAllActive(allActive);
        r.setLevel(level);
        r.setAccountIds(accountIds);
        r.setTargets(allActive ? List.of() : targets);
        r.setEnabled(enabled);
        if (isLadder) {
            r.setLadderMetric(ladderMetric);
            r.setSteps(steps);
            r.setIncludeLearning(!Boolean.FALSE.equals(input.includeLearning()));
        }
        return c.done(r);
    }
}
