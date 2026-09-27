package com.fbads.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Ràng buộc Bean Validation tự viết: mật khẩu mới đủ mạnh (8–128 ký tự, không chỉ toàn số, không nằm trong danh sách phổ biến).
 * Dùng: @StrongPassword trên trường của request; Spring kiểm tra tự động khi controller có @Valid.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {
    String message() default "Mật khẩu không hợp lệ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrongPassword, String> {
        static final List<String> WEAK = List.of("12345678", "123456789", "1234567890", "password", "matkhau123", "qwertyui", "11111111", "00000000", "abcd1234");

        /** Lý do mật khẩu yếu, chuỗi rỗng = đạt */
        public static String problem(String n) {
            if (n == null) n = "";
            if (n.length() < 8) return "Mật khẩu mới cần ít nhất 8 ký tự";
            if (n.length() > 128) return "Mật khẩu tối đa 128 ký tự";
            if (n.matches("^\\d+$")) return "Không nên chỉ gồm chữ số, hãy thêm chữ cái hoặc ký tự khác";
            if (WEAK.contains(n.toLowerCase())) return "Mật khẩu này quá phổ biến, hãy chọn mật khẩu khác";
            return "";
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            String p = problem(value);
            if (p.isEmpty()) return true;
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(p).addConstraintViolation();
            return false;
        }
    }
}
