package com.fbads.dto;

/**
 * Body của các request đơn giản. Jackson đọc JSON thẳng vào record; gửi sai kiểu (vd. "on": "abc")
 * thì ApiExceptionHandler trả 400 { error, errors: { trường: "Sai kiểu dữ liệu" } }.
 * Trường chữ không gửi = "" (constructor gọn bên dưới), như `req.body.x || ''` của bản Node.
 * Body bỏ trống: controller dùng hằng EMPTY của từng record, giống gửi {}.
 * Luật kiểm tra nằm ở chú thích Bean Validation (controller dùng @Valid) hoặc ở service.
 */
public final class Requests {
    private Requests() {}
}
