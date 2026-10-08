package com.fbads.rule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fbads.ads.AdLevel;

import java.util.List;

/** Xem trước rule: khoảng tính, chế độ, có thay đổi thật không, từng camp và kết quả xét */
public record RulePreview(String range, String mode, boolean willChange, List<PreviewItem> items, Counts counts,
        List<String> warnings) {
    public RulePreview withWarnings(List<String> w) { return new RulePreview(range, mode, willChange, items, counts, w); }

    /** Một camp trong xem trước: trạng thái xét (status/code/reason), giá trị so sánh, từng điều kiện, việc sẽ làm */
    public record PreviewItem(String id, String name, AdLevel level, String effective, boolean learning, Double budget,
            DecisionStatus status, String code, String reason, boolean hit, Double value, boolean inf, double spend,
            double results, List<PreviewCond> conds, PreviewResult result) {}

    /** Một điều kiện trong xem trước. Các trường cuối chỉ có khi dùng: khoảng so sánh, bậc theo kết quả, rule tăng theo bậc */
    public record PreviewCond(String metric, String op, String vs, Double factor, Double threshold, Double actual, boolean inf,
            boolean hit, boolean unknown,
            @JsonInclude(JsonInclude.Include.NON_NULL) String compareRange,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tierMetric,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double tierCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double tierAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer ladderStep,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer ladderNeed) {}

    /** Việc rule sẽ làm với camp này; notify = chỉ báo, không đổi gì */
    public record PreviewResult(String detail, @JsonProperty("notify") boolean notifyOnly) {}

    public record Counts(long match, int total) {}
}
