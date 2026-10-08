package com.fbads.rule;

import com.fbads.common.JsNumber;

import java.util.List;

/**
 * Body của POST /api/rules và /api/rules/preview. Chỉ mang dữ liệu; luật kiểm tra nằm ở RuleValidator.
 * Rule cũ chỉ có 1 điều kiện ở metric/op/value; rule mới gửi danh sách conditions (1..5).
 */
public record RuleRequest(
        String id,
        String name,
        List<ConditionRequest> conditions,
        String metric,
        String op,
        @JsNumber Double value,
        /** Gộp điều kiện: "all" (VÀ) | "any" (HOẶC) */
        String match,
        String range,
        @JsNumber Double minSpend,
        /** "pause" | "increase" | "decrease" | "notify" */
        String action,
        /** Đổi ngân sách theo "percent" (pct) hoặc "amount" (amount) */
        String budgetMode,
        @JsNumber Double pct,
        @JsNumber Double amount,
        @JsNumber Double maxBudget,
        @JsNumber Double minBudget,
        @JsNumber Double cooldownHours,
        /** "nextday" = tự bật lại lúc resumeAt hôm sau (chỉ rule tắt) */
        String resume,
        String resumeAt,
        /** Khung giờ rule được chạy, "HH:MM" */
        String from,
        String to,
        String level,
        /** Chỉ false mới áp dụng cho danh sách targets; không gửi = mọi camp đang chạy */
        Boolean allActive,
        List<String> targets,
        List<String> accountIds,
        Boolean enabled,
        /** Rule tăng theo bậc kết quả (action = "ladder"): loại kết quả tính bậc */
        String ladderMetric,
        /** Các bậc: có từ count kết quả thì tăng value (% hoặc số tiền); bậc cuối có thể lặp lại mỗi everyHours giờ */
        List<StepRequest> steps,
        /** Tăng cả nhóm đang học (mặc định có) */
        Boolean includeLearning) {

    public static final RuleRequest EMPTY = new RuleRequest(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    /**
     * Một điều kiện: số liệu lớn/nhỏ hơn ngưỡng; vs = "target": so với mục tiêu tài khoản × factor%;
     * vs = "range": so với chính số liệu đó ở compareRange × factor%; tiers: ngưỡng chi tiêu nâng theo số kết quả tierMetric.
     */
    public record ConditionRequest(String metric, String op, String vs, @JsNumber Double factor, @JsNumber Double value,
                                   String compareRange, String tierMetric, List<TierRequest> tiers) {
        public static final ConditionRequest EMPTY = new ConditionRequest(null, null, null, null, null, null, null, null);

        public ConditionRequest(String metric, String op, String vs, Double factor, Double value) {
            this(metric, op, vs, factor, value, null, null, null);
        }
    }

    public record TierRequest(@JsNumber Double count, @JsNumber Double value) {}

    public record StepRequest(@JsNumber Double count, String mode, @JsNumber Double value, @JsNumber Double everyHours) {}

    /** Bản sao đã bật (xem trước luôn xét như rule đang bật) */
    public RuleRequest enabledCopy() {
        return new RuleRequest(id, name, conditions, metric, op, value, match, range, minSpend, action,
                budgetMode, pct, amount, maxBudget, minBudget, cooldownHours, resume, resumeAt, from, to,
                level, allActive, targets, accountIds, true, ladderMetric, steps, includeLearning);
    }
}
