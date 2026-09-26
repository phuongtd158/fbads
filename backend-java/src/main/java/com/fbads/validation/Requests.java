package com.fbads.validation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Các request đơn giản kiểm tra bằng Bean Validation (chú thích trên từng trường, controller dùng @Valid).
 * Lỗi được ApiExceptionHandler đổi thành { error, errors: { trường: câu báo } } như bản Node.
 */
public final class Requests {
    private Requests() {}

    /** Đặt ngân sách tay cho 1 camp */
    public record Budget(
            @NotNull(message = "Nhập ngân sách hợp lệ (số)")
            @Positive(message = "Ngân sách phải lớn hơn 0")
            @DecimalMax(value = "10000000000", message = "Ngân sách quá lớn, hãy kiểm tra lại số 0")
            Double amount,
            String name) {}

    /** Đổi mật khẩu đăng nhập */
    public record PasswordChange(String currentPassword, @StrongPassword String newPassword) {}
}
