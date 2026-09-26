package com.fbads.automation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Một điều kiện của rule: số liệu (metric) lớn/nhỏ hơn ngưỡng.
 * vs = "target": ngưỡng = mục tiêu của tài khoản chứa camp × factor% (khi đó value = 0).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Condition(String metric, String op, String vs, Double factor, Double value) {
    public boolean vsTarget() { return "target".equals(vs); }
}
