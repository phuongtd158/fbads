package com.fbads.notify;

/**
 * Một ô cấu hình của kênh, để giao diện tự vẽ form "Thêm kênh" mà không cần biết kênh là gì.
 *
 * @param key         khoá trong JSON cấu hình, vd "token"
 * @param label       nhãn hiện trên form
 * @param secret      bí mật (token, mật khẩu): không bao giờ gửi về giao diện, để trống khi sửa = giữ giá trị cũ
 * @param placeholder chữ mờ gợi ý trong ô
 * @param hint        dòng giải thích dưới ô ("" = không có)
 */
public record ConfigField(String key, String label, boolean secret, String placeholder, String hint) {}
