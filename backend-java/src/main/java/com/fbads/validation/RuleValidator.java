package com.fbads.validation;

import com.fbads.automation.Condition;
import com.fbads.automation.Rule;
import com.fbads.common.Fmt;
import com.fbads.common.Json;
import com.fbads.facebook.AdObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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
    static final List<String> METRICS = List.of("cpa", "roas", "spend", "results", "ctr", "cpc", "cpm", "messages", "costPerMessage", "leads", "costPerLead", "frequency");
    static final List<String> COST_METRICS = List.of("cpa", "spend", "cpc", "cpm", "costPerMessage", "costPerLead");
    static final List<String> TARGET_METRICS = List.of("cpa", "roas", "spend");
    static final List<String> RANGES = List.of("today", "yesterday", "last_3d", "last_7d");
    static final int MAX_CONDITIONS = 5;
    static final Map<String, String> RANGE_LABEL = Map.of("today", "hôm nay", "yesterday", "hôm qua", "last_3d", "3 ngày gần nhất", "last_7d", "7 ngày gần nhất");
    static final Map<String, String> METRIC_LABEL = Map.ofEntries(
            Map.entry("cpa", "CPA"), Map.entry("roas", "ROAS"), Map.entry("spend", "Chi tiêu"), Map.entry("results", "Số kết quả"),
            Map.entry("ctr", "CTR"), Map.entry("cpc", "CPC"), Map.entry("cpm", "CPM"), Map.entry("messages", "Số tin nhắn"),
            Map.entry("costPerMessage", "Chi phí/tin nhắn"), Map.entry("leads", "Số lead"), Map.entry("costPerLead", "Chi phí/lead"),
            Map.entry("frequency", "Tần suất"));

    /** Tài khoản đang quản lý (id, tên) — để cảnh báo phạm vi/mục tiêu */
    public record Account(String id, String name) {}

    private RuleValidator() {}

    static String targetKey(String metric) { return "spend".equals(metric) ? "cpa" : metric; }

    private static <T> List<T> uniq(List<T> a) { return new ArrayList<>(new LinkedHashSet<>(a)); }

    private static boolean condOverlap(String aOp, double aVal, Rule b) {
        if (Objects.equals(aOp, b.getOp())) return true;
        double lo, hi;
        if (">".equals(aOp)) { lo = aVal; hi = b.getValue(); } else { lo = b.getValue(); hi = aVal; }
        return lo < hi;
    }

    public static Result<Rule> validate(JsonNode input, List<AdObject> objs, List<Rule> rules, Map<String, Map<String, Number>> accountTargets, List<Account> accounts) {
        Result.Collector c = new Result.Collector();
        Map<String, String> e = c.e;
        String name = Json.str(input.get("name")).trim();
        if (name.length() > Checks.NAME_MAX) e.put("name", "Tên tối đa " + Checks.NAME_MAX + " ký tự");

        // ----- Điều kiện (1..5), gộp bằng VÀ / HOẶC
        List<JsonNode> rawConds = new ArrayList<>();
        if (input.path("conditions").isArray() && !input.get("conditions").isEmpty()) input.get("conditions").forEach(rawConds::add);
        else {
            ObjectNode one = JsonNodeFactory.instance.objectNode();
            if (input.has("metric")) one.set("metric", input.get("metric"));
            if (input.has("op")) one.set("op", input.get("op"));
            if (input.has("value")) one.set("value", input.get("value"));
            rawConds.add(one);
        }
        if (rawConds.size() > MAX_CONDITIONS) e.put("conditions", "Tối đa " + MAX_CONDITIONS + " điều kiện cho mỗi rule");
        List<Condition> conds = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_CONDITIONS, rawConds.size()); i++) {
            JsonNode cn = rawConds.get(i) == null || rawConds.get(i).isNull() ? JsonNodeFactory.instance.objectNode() : rawConds.get(i);
            int idx = i;
            java.util.function.BiConsumer<String, String> put = (field, msg) -> { e.put("c" + idx + "." + field, msg); if (idx == 0) e.put(field, msg); };
            String metric = cn.path("metric").isString() ? cn.get("metric").stringValue() : null;
            if (!METRICS.contains(metric)) put.accept("metric", "Số liệu không hợp lệ");
            String opRaw = Json.str(cn.get("op"));
            String op = "<".equals(opRaw) ? "<" : ">".equals(opRaw) ? ">" : null;
            if (op == null) put.accept("op", "Phép so sánh không hợp lệ");
            if ("target".equals(Json.str(cn.get("vs")))) { // so với mục tiêu của từng tài khoản: ngưỡng = mục tiêu × factor%
                if (!TARGET_METRICS.contains(metric)) put.accept("metric", "Chỉ CPA, ROAS và Chi tiêu so được với mục tiêu");
                double factor = Json.isBlank(cn.get("factor")) ? 100 : Json.num(cn.get("factor"));
                if (!Double.isFinite(factor) || factor <= 0 || factor > 1000) put.accept("value", "Phần trăm so với mục tiêu phải từ 1 đến 1000");
                conds.add(new Condition(metric, op, "target", Double.isFinite(factor) ? factor : 100, 0.0));
                continue;
            }
            double value = Json.num(cn.get("value"));
            if (!Double.isFinite(value)) put.accept("value", "Nhập ngưỡng so sánh");
            else if (value < 0) put.accept("value", "Ngưỡng không được âm");
            else if (COST_METRICS.contains(metric) && ">".equals(op) && value <= 0)
                put.accept("value", "Ngưỡng " + METRIC_LABEL.get(metric) + " phải lớn hơn 0, nếu không rule sẽ khớp với mọi camp.");
            else if ("roas".equals(metric) && value > 100) put.accept("value", "ROAS lớn hơn 100 là bất thường, hãy kiểm tra lại");
            else if ("ctr".equals(metric) && value > 100) put.accept("value", "CTR là phần trăm, tối đa 100");
            else if ("frequency".equals(metric) && value > 50) put.accept("value", "Tần suất lớn hơn 50 là bất thường, hãy kiểm tra lại");
            conds.add(new Condition(metric, op, null, null, Double.isFinite(value) ? value : 0.0));
        }
        Condition first = conds.isEmpty() ? new Condition(null, null, null, null, 0.0) : conds.getFirst();
        String metric = first.metric(), op = first.op();
        double value = first.value() == null ? 0 : first.value();
        String match = "any".equals(Json.str(input.get("match"))) ? "any" : "all";
        // Điều kiện tự mâu thuẫn (VÀ): cùng số liệu vừa lớn hơn a vừa nhỏ hơn b mà a ≥ b thì không bao giờ khớp
        if (match.equals("all") && e.isEmpty()) {
            for (String m : uniq(conds.stream().filter(x -> !x.vsTarget()).map(Condition::metric).toList())) {
                List<Condition> same = conds.stream().filter(x -> Objects.equals(x.metric(), m) && !x.vsTarget()).toList();
                double lo = same.stream().filter(x -> ">".equals(x.op())).mapToDouble(Condition::value).max().orElse(Double.NEGATIVE_INFINITY);
                double hi = same.stream().filter(x -> "<".equals(x.op())).mapToDouble(Condition::value).min().orElse(Double.POSITIVE_INFINITY);
                if (lo >= hi) {
                    c.warn("Các điều kiện về " + METRIC_LABEL.get(m) + " mâu thuẫn nhau (vừa lớn hơn " + Fmt.num(lo) + ", vừa nhỏ hơn " + Fmt.num(hi) + ") nên rule sẽ không bao giờ khớp.");
                    break;
                }
            }
        }

        String range = Json.isBlank(input.get("range")) ? "today" : RANGES.contains(Json.str(input.get("range"))) ? Json.str(input.get("range")) : null;
        if (range == null) e.put("range", "Khoảng thời gian không hợp lệ");

        boolean hasDataMetric = conds.stream().anyMatch(x -> x.metric() != null && !x.metric().equals("spend"));
        double minSpend = Json.isBlank(input.get("minSpend")) ? 0 : Json.num(input.get("minSpend"));
        if (!Double.isFinite(minSpend) || minSpend < 0) e.put("minSpend", "Chi tiêu tối thiểu phải là số không âm");
        else if (hasDataMetric && minSpend <= 0) e.put("minSpend", "Cần đặt chi tiêu tối thiểu lớn hơn 0 để không quyết định khi camp mới chạy, chưa đủ dữ liệu.");

        String action = input.path("action").isString() ? input.get("action").stringValue() : null;
        if (!List.of("pause", "increase", "decrease", "notify").contains(action)) e.put("action", "Hành động không hợp lệ");
        if ("today".equals(range) && hasDataMetric && ("pause".equals(action) || "decrease".equals(action)))
            c.warn("Rule đang chỉ dựa trên số liệu hôm nay. Chuyển đổi thường về trễ nên dễ tắt/giảm oan; nên dùng “3 ngày gần nhất” hoặc dài hơn.");
        boolean budgetAction = "increase".equals(action) || "decrease".equals(action);
        String budgetMode = budgetAction && "amount".equals(Json.str(input.get("budgetMode"))) ? "amount" : "percent";
        double amount = Json.num(input.get("amount"));
        if (budgetMode.equals("amount")) {
            if (!Double.isFinite(amount) || amount <= 0) e.put("amount", "Nhập số tiền thay đổi lớn hơn 0");
            else if (amount > Checks.BUDGET_MAX) e.put("amount", "Số tiền quá lớn, hãy kiểm tra lại số 0");
            else amount = Math.round(amount);
        } else amount = 0;
        double pct = Json.num(input.get("pct"));
        if (budgetMode.equals("amount")) pct = 0;
        else if (budgetAction) {
            if (!Double.isFinite(pct) || pct <= 0) e.put("pct", "Nhập % thay đổi lớn hơn 0");
            else if ("decrease".equals(action) && pct > Checks.RULE_PCT_DECREASE_MAX)
                e.put("pct", "Giảm tối đa " + Checks.RULE_PCT_DECREASE_MAX + "% mỗi lần (giảm 100% là đưa ngân sách về 0)");
            else if ("increase".equals(action) && pct > Checks.RULE_PCT_INCREASE_MAX) e.put("pct", "Tăng tối đa " + Checks.RULE_PCT_INCREASE_MAX + "% mỗi lần");
            else if (pct > Checks.BIG_PCT_WARN)
                c.warn(("increase".equals(action) ? "Tăng" : "Giảm") + " " + Fmt.num(pct) + "% mỗi lần là khá lớn, Facebook có thể học lại từ đầu. Nên khoảng 20%.");
        } else pct = 0;

        double maxBudget = Json.isBlank(input.get("maxBudget")) ? 0 : Json.num(input.get("maxBudget"));
        double minBudget = Json.isBlank(input.get("minBudget")) ? 0 : Json.num(input.get("minBudget"));
        if (!Double.isFinite(maxBudget) || maxBudget < 0) e.put("maxBudget", "Trần ngân sách phải là số không âm");
        if (!Double.isFinite(minBudget) || minBudget < 0) e.put("minBudget", "Sàn ngân sách phải là số không âm");
        if (!e.containsKey("maxBudget") && !e.containsKey("minBudget") && maxBudget > 0 && minBudget > 0 && maxBudget < minBudget)
            e.put("maxBudget", "Trần ngân sách phải lớn hơn hoặc bằng sàn");
        if ("increase".equals(action) && !e.containsKey("maxBudget") && maxBudget <= 0) c.warn("Chưa đặt trần ngân sách: ngân sách có thể tăng mãi qua nhiều ngày. Nên đặt trần.");
        if ("decrease".equals(action) && !e.containsKey("minBudget") && minBudget <= 0) c.warn("Chưa đặt sàn ngân sách: ngân sách có thể giảm rất thấp qua nhiều lần. Nên đặt sàn.");

        double cooldown = Json.isBlank(input.get("cooldownHours")) ? 0 : Json.num(input.get("cooldownHours"));
        if (!Double.isFinite(cooldown) || cooldown < 0 || cooldown > Checks.COOLDOWN_MAX) e.put("cooldownHours", "Thời gian nghỉ từ 0 đến " + Checks.COOLDOWN_MAX + " giờ");
        else if (budgetAction && cooldown < 1)
            e.put("cooldownHours", "Rule đổi ngân sách cần nghỉ ít nhất 1 giờ giữa hai lần, nếu không ngân sách sẽ thay đổi liên tục mỗi lần kiểm tra.");
        else if ("notify".equals(action) && cooldown < 1)
            e.put("cooldownHours", "Rule chỉ thông báo cần nghỉ ít nhất 1 giờ giữa hai lần, nếu không bạn sẽ nhận thông báo lặp lại mỗi lần kiểm tra.");

        // Tự bật lại (chỉ với rule tắt): '' = không, 'nextday' = bật lại lúc resumeAt của ngày hôm sau
        String resume = "pause".equals(action) && "nextday".equals(Json.str(input.get("resume"))) ? "nextday" : "";
        String resumeAt = Json.isBlank(input.get("resumeAt")) ? "06:00" : Json.str(input.get("resumeAt"));
        if (!resume.isEmpty() && !Checks.isTime(resumeAt)) e.put("resumeAt", "Giờ bật lại không hợp lệ (dạng HH:MM, ví dụ 06:00)");
        if (!resume.isEmpty() && range != null && !range.equals("today") && hasDataMetric)
            c.warn("Rule tự bật lại nhưng số liệu tính theo “" + RANGE_LABEL.get(range) + "” vẫn gồm những ngày xấu, nên "
                    + ("adset".equals(Json.str(input.get("level"))) ? "nhóm QC" : "camp")
                    + " có thể bị tắt lại ngay sau khi bật. Kiểu “tắt hôm nay, mai chạy lại” nên dùng số liệu “hôm nay”.");

        String from = Json.truthy(input.get("from")) ? Json.str(input.get("from")) : "", to = Json.truthy(input.get("to")) ? Json.str(input.get("to")) : "";
        if (!from.isEmpty() || !to.isEmpty()) {
            if (from.isEmpty() || to.isEmpty()) e.put("window", "Hãy nhập cả giờ bắt đầu và giờ kết thúc (hoặc để trống cả hai)");
            else if (!Checks.isTime(from) || !Checks.isTime(to)) e.put("window", "Khung giờ không hợp lệ");
            else if (from.compareTo(to) >= 0) e.put("window", "Giờ bắt đầu phải nhỏ hơn giờ kết thúc (chưa hỗ trợ khung giờ qua đêm)");
        }

        // Cấp áp dụng: chiến dịch (mặc định) hoặc nhóm QC
        String level = "adset".equals(Json.str(input.get("level"))) ? "adset" : "campaign";
        String unit = level.equals("adset") ? "nhóm QC" : "camp";
        boolean allActive = !(input.path("allActive").isBoolean() && !input.get("allActive").booleanValue());
        List<String> targets = uniq(Json.strings(input.get("targets")));
        if (!allActive) {
            if (targets.isEmpty()) e.put("targets", "Hãy chọn ít nhất 1 " + unit + " áp dụng");
            else if (objs != null) {
                List<String> unknown = targets.stream().filter(id -> objs.stream().noneMatch(o -> o.id.equals(id))).toList();
                List<String> other = targets.stream().filter(id -> objs.stream().anyMatch(o -> o.id.equals(id) && o.level != null && !o.level.equals(level))).toList();
                if (!unknown.isEmpty()) e.put("targets", "Có mục không còn tồn tại trên tài khoản: " + String.join(", ", unknown.stream().limit(3).toList()) + ".");
                else if (!other.isEmpty()) e.put("targets", "Có " + other.size() + " mục không phải " + (level.equals("adset") ? "nhóm QC" : "chiến dịch") + ", hãy chọn lại.");
            }
        }

        // Phạm vi tài khoản (chỉ khi áp dụng cho "tất cả camp đang chạy"): trống = mọi tài khoản đang quản lý
        List<String> accountIds = allActive ? uniq(Json.strings(input.get("accountIds")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList()) : List.of();
        if (accountIds.size() > Checks.ACCOUNTS_MAX) e.put("accountIds", "Tối đa " + Checks.ACCOUNTS_MAX + " tài khoản");
        if (accounts != null && !accountIds.isEmpty()) {
            List<String> gone = accountIds.stream().filter(id -> accounts.stream().noneMatch(a -> a.id().equals(id))).toList();
            if (!gone.isEmpty()) c.warn("Rule đang giới hạn theo tài khoản " + String.join(", ", gone.stream().limit(3).toList())
                    + " nhưng tài khoản này không còn được quản lý, nên không " + unit + " nào được xét.");
        }

        // Rule đổi ngân sách: mục không có ngân sách riêng (CBO/ABO) bị bỏ qua
        if (budgetAction && objs != null && !e.containsKey("targets")) {
            List<AdObject> pool = allActive
                    ? objs.stream().filter(o -> level.equals(o.level) && o.isActive() && (accountIds.isEmpty() || accountIds.contains(o.accountId))).toList()
                    : objs.stream().filter(o -> targets.contains(o.id)).toList();
            long none = pool.stream().filter(o -> o.dailyBudget == null).count();
            String why = level.equals("adset") ? "nằm trong chiến dịch CBO (ngân sách đặt ở chiến dịch)" : "là chiến dịch ABO (ngân sách đặt ở từng nhóm QC)";
            if (!pool.isEmpty() && none == pool.size())
                c.warn("Không " + unit + " nào " + (allActive ? "đang chạy " : "đã chọn ") + "có ngân sách riêng (đều " + why + "), nên rule này sẽ không đổi được ngân sách. "
                        + (level.equals("adset") ? "Hãy dùng rule cấp chiến dịch." : "Hãy dùng rule cấp nhóm QC."));
            else if (none > 0) c.warn(none + " " + unit + " không có ngân sách riêng (" + why + ") sẽ bị bỏ qua.");
        }
        // So với mục tiêu: mỗi tài khoản trong phạm vi phải có mục tiêu tương ứng
        List<Condition> tcs = conds.stream().filter(Condition::vsTarget).toList();
        if (!tcs.isEmpty() && accountTargets != null && accounts != null) {
            List<String> inScope = !accountIds.isEmpty() ? accountIds
                    : allActive ? accounts.stream().map(Account::id).toList()
                    : uniq(targets.stream().map(id -> (objs == null ? List.<AdObject>of() : objs).stream().filter(o -> o.id.equals(id)).map(o -> o.accountId).findFirst().orElse(null))
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
        boolean enabled = !(input.path("enabled").isBoolean() && !input.get("enabled").booleanValue());
        String inputId = Json.truthy(input.get("id")) ? Json.str(input.get("id")) : null;
        if (enabled && !e.containsKey("metric") && !e.containsKey("op") && !e.containsKey("value") && !e.containsKey("action") && conds.size() == 1 && !conds.getFirst().vsTarget()) {
            java.util.function.Function<String, String> kind = a -> "increase".equals(a) ? "up" : "down"; // pause & decrease đều là "giảm chi"
            for (Rule o : rules) {
                if (o.getId() != null && o.getId().equals(inputId)) continue;
                boolean simple = o.conditionList().size() == 1 && !o.conditionList().getFirst().vsTarget();
                if (!o.isEnabled() || !simple || !Objects.equals(o.getMetric(), metric) || !(">".equals(o.getOp()) || "<".equals(o.getOp()))) continue;
                if ("notify".equals(o.getAction()) || "notify".equals(action)) continue;
                if (!Objects.equals(o.getLevel() == null ? "campaign" : o.getLevel(), level)) continue;
                boolean scopeOverlap = allActive || o.isAllActive() || targets.stream().anyMatch(t -> o.getTargets() != null && o.getTargets().contains(t));
                if (!scopeOverlap) continue;
                if (!kind.apply(o.getAction()).equals(kind.apply(action)) && condOverlap(op, value, o)) {
                    c.warn("Rule “" + o.getName() + "” có thể mâu thuẫn: cùng xét " + METRIC_LABEL.get(metric) + " nhưng "
                            + ("increase".equals(o.getAction()) ? "tăng" : "pause".equals(o.getAction()) ? "tắt" : "giảm") + " ngân sách trong vùng giá trị chồng lấn.");
                    break;
                }
            }
        }

        Rule r = new Rule();
        r.setId(inputId);
        r.setName(name.isEmpty() ? "Rule mới" : name);
        r.setMetric(metric);
        r.setOp(op);
        r.setValue(value);
        r.setConditions(conds);
        r.setMatch(match);
        r.setRange(range == null ? "today" : range);
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
        return c.done(r);
    }
}
