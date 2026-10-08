package com.fbads.ads;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Đặt ngân sách tay cho 1 camp */
public record Budget(
        @NotNull(message = "Nhập ngân sách hợp lệ (số)")
        @Positive(message = "Ngân sách phải lớn hơn 0")
        @DecimalMax(value = "10000000000", message = "Ngân sách quá lớn, hãy kiểm tra lại số 0")
        Double amount,
        String name) {}
