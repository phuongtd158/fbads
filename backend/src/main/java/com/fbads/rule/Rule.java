package com.fbads.rule;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fbads.common.JsonConverters;
import com.fbads.dto.AdLevel;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule theo hiệu quả: mỗi chu kỳ kiểm tra, camp/nhóm QC nào khớp điều kiện thì tắt / tăng / giảm ngân sách / báo Telegram.
 * metric/op/value = điều kiện đầu tiên (giữ cho dữ liệu cũ); conditions = đầy đủ.
 */
@Entity
@Table(name = "rules")
@JsonPropertyOrder({"id", "name", "metric", "op", "value", "conditions", "match", "range", "minSpend", "action", "pct",
        "budgetMode", "amount", "maxBudget", "minBudget", "cooldownHours", "resume", "resumeAt", "from", "to", "allActive",
        "level", "accountIds", "targets", "enabled", "ladderMetric", "steps", "includeLearning"})
public class Rule {
    @Id
    private String id;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc (config/TenantConfig) */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    @JsonIgnore
    private Long workspaceId;
    @Column(insertable = false, updatable = false)
    @JsonIgnore
    private Long seq;
    private String name;
    private String metric;
    private String op;
    @Column(name = "threshold")
    private double value;
    @Convert(converter = ConditionList.class)
    private List<Condition> conditions = new ArrayList<>();
    @Column(name = "match_mode")
    @Convert(converter = MatchMode.Converter.class)
    private MatchMode match = MatchMode.ALL;
    @Column(name = "range_key")
    @Convert(converter = RuleRange.Converter.class)
    private RuleRange range = RuleRange.TODAY;
    private double minSpend;
    @Convert(converter = RuleAction.Converter.class)
    private RuleAction action;
    private double pct;
    private String budgetMode = "percent";
    private double amount;
    private double maxBudget;
    private double minBudget;
    private double cooldownHours;
    @Column(name = "resume_mode")
    private String resume = "";          // '' | nextday
    private String resumeAt = "";
    @Column(name = "from_time")
    private String from = "";
    @Column(name = "to_time")
    private String to = "";
    private boolean allActive = true;
    @Column(name = "ad_level")
    @Convert(converter = AdLevel.Converter.class)
    private AdLevel level = AdLevel.CAMPAIGN;
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> accountIds = new ArrayList<>();
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> targets = new ArrayList<>();
    private boolean enabled = true;
    // Rule tăng theo bậc kết quả (action = ladder); rule khác để null (không có trong JSON)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String ladderMetric;         // results | leads | messages
    @Convert(converter = StepList.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Step> steps;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean includeLearning;

    /**
     * Một bậc của rule tăng theo bậc: có từ count kết quả thì tăng value (mode percent: %, amount: số tiền);
     * everyHours = lặp lại (bậc cuối)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(double count, String mode, double value, Double everyHours) {}

    public static class StepList implements AttributeConverter<List<Step>, String> {
        private static final JsonMapper M = JsonMapper.builder().build();

        @Override
        public String convertToDatabaseColumn(List<Step> v) { return v == null ? null : M.writeValueAsString(v); }

        @Override
        public List<Step> convertToEntityAttribute(String s) {
            return s == null || s.isBlank() ? null : M.readValue(s, new TypeReference<List<Step>>() {});
        }
    }

    @JsonIgnore
    public boolean isLadder() { return action == RuleAction.LADDER; }

    /** Các điều kiện (rule cũ chỉ có metric/op/value → coi như 1 điều kiện) */
    public List<Condition> conditionList() {
        if (conditions != null && !conditions.isEmpty()) return conditions;
        return metric == null ? List.of() : List.of(new Condition(metric, op, null, null, value));
    }

    public static class ConditionList implements AttributeConverter<List<Condition>, String> {
        private static final JsonMapper M = JsonMapper.builder().build();

        @Override
        public String convertToDatabaseColumn(List<Condition> v) { return v == null ? "[]" : M.writeValueAsString(v); }

        @Override
        public List<Condition> convertToEntityAttribute(String s) {
            return s == null || s.isBlank() ? new ArrayList<>() : M.readValue(s, new TypeReference<List<Condition>>() {});
        }
    }

    public String getLadderMetric() { return ladderMetric; }
    public void setLadderMetric(String v) { this.ladderMetric = v; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> v) { this.steps = v; }
    public Boolean getIncludeLearning() { return includeLearning; }
    public void setIncludeLearning(Boolean v) { this.includeLearning = v; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
    public MatchMode getMatch() { return match; }
    public void setMatch(MatchMode match) { this.match = match; }
    public RuleRange getRange() { return range; }
    public void setRange(RuleRange range) { this.range = range; }
    public double getMinSpend() { return minSpend; }
    public void setMinSpend(double minSpend) { this.minSpend = minSpend; }
    public RuleAction getAction() { return action; }
    public void setAction(RuleAction action) { this.action = action; }
    public double getPct() { return pct; }
    public void setPct(double pct) { this.pct = pct; }
    public String getBudgetMode() { return budgetMode; }
    public void setBudgetMode(String budgetMode) { this.budgetMode = budgetMode; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public double getMaxBudget() { return maxBudget; }
    public void setMaxBudget(double maxBudget) { this.maxBudget = maxBudget; }
    public double getMinBudget() { return minBudget; }
    public void setMinBudget(double minBudget) { this.minBudget = minBudget; }
    public double getCooldownHours() { return cooldownHours; }
    public void setCooldownHours(double cooldownHours) { this.cooldownHours = cooldownHours; }
    public String getResume() { return resume; }
    public void setResume(String resume) { this.resume = resume; }
    public String getResumeAt() { return resumeAt; }
    public void setResumeAt(String resumeAt) { this.resumeAt = resumeAt; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public boolean isAllActive() { return allActive; }
    public void setAllActive(boolean allActive) { this.allActive = allActive; }
    public AdLevel getLevel() { return level; }
    public void setLevel(AdLevel level) { this.level = level; }
    public List<String> getAccountIds() { return accountIds; }
    public void setAccountIds(List<String> accountIds) { this.accountIds = accountIds; }
    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
