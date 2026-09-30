package com.fbads.dto;

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
        Boolean enabled) {

    public static final RuleRequest EMPTY = new RuleRequest(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);

    /** Một điều kiện: số liệu lớn/nhỏ hơn ngưỡng, hoặc (vs = "target") so với mục tiêu tài khoản × factor% */
    public record ConditionRequest(String metric, String op, String vs, @JsNumber Double factor, @JsNumber Double value) {
        public static final ConditionRequest EMPTY = new ConditionRequest(null, null, null, null, null);
    }

    /** Bản sao đã bật (xem trước luôn xét như rule đang bật) */
    public RuleRequest enabledCopy() {
        return new RuleRequest(id, name, conditions, metric, op, value, match, range, minSpend, action, budgetMode, pct, amount, maxBudget, minBudget,
                cooldownHours, resume, resumeAt, from, to, level, allActive, targets, accountIds, true);
    }
}
