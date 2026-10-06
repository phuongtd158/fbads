package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Một điều kiện của rule: số liệu (metric) lớn/nhỏ hơn ngưỡng.
 *  - vs = "target": ngưỡng = mục tiêu của tài khoản chứa camp × factor% (khi đó value = 0);
 *  - vs = "range": ngưỡng = chính số liệu đó ở khoảng compareRange × factor% (số liệu dạng tổng chia trung bình theo ngày);
 *  - tiers (chỉ "Chi tiêu lớn hơn" số cụ thể): ngưỡng nâng theo số kết quả tierMetric, vd từ 2 lead thì 200.000.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Condition(String metric, String op, String vs, String compareRange, Double factor, Double value, String tierMetric,
        List<Tier> tiers) {
    /** Một bậc: có từ `count` kết quả thì ngưỡng chi tiêu là `value` */
    public record Tier(double count, double value) {}

    public Condition(String metric, String op, String vs, Double factor, Double value) {
        this(metric, op, vs, null, factor, value, null, null);
    }

    public boolean vsTarget() { return "target".equals(vs); }

    public boolean vsRange() { return "range".equals(vs); }

    public boolean hasTiers() { return tiers != null && !tiers.isEmpty(); }
}
