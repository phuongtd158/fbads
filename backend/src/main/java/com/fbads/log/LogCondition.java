package com.fbads.log;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.JsonConverters;
import com.fbads.rule.MatchMode;
import com.fbads.rule.RuleRange;

import java.util.List;

/**
 * Nhật ký: điều kiện đã khớp khi rule (hoặc dừng khẩn) ra tay. 6 trường đầu là điều kiện đầu tiên (giữ cho nhật ký cũ),
 * conditions = từng điều kiện, ladder = bậc đã đạt (rule tăng theo bậc).
 * actual = null khi không ra số (vd CPA khi chưa có kết quả: actualInf = true).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogCondition(String metric, String op, RuleRange range, @JsonInclude(JsonInclude.Include.ALWAYS) Double threshold,
        @JsonInclude(JsonInclude.Include.ALWAYS) Double actual, Boolean actualInf, Double minSpend, Double spend,
        Double cooldownHours, MatchMode match, List<Hit> conditions, Ladder ladder) {

    /** Một điều kiện: so với gì (vs, factor, compareRange), ngưỡng, số thật, có khớp không (hit), có thiếu số không (unknown) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(String metric, String op, String vs, Double factor,
            @JsonInclude(JsonInclude.Include.ALWAYS) Double threshold,
            @JsonInclude(JsonInclude.Include.ALWAYS) Double actual, Boolean actualInf, Boolean hit, Boolean unknown,
            String compareRange, String tierMetric, Double tierCount, Double tierAt, Integer ladderStep, Integer ladderNeed) {}

    /** Bậc thứ step (đếm từ 1) trên tổng total bậc: đã có count, cần need (đơn vị theo metric) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ladder(Integer step, Double count, Double need, String metric, Integer total) {}

    /** Dừng khẩn: chi tiêu hôm nay vượt giới hạn */
    public static LogCondition spendOver(double limit, double spend) {
        return new LogCondition("spend", ">", RuleRange.TODAY, limit, spend, null, 0.0, spend, null, null, null, null);
    }

    public static class Converter extends JsonConverters.Of<LogCondition> {
        public Converter() { super(LogCondition.class); }
    }
}
