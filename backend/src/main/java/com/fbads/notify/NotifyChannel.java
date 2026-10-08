package com.fbads.notify;

import tools.jackson.databind.JsonNode;

/**
 * Hợp đồng chung của mọi kênh thông báo (Telegram, Gmail, Zalo…).
 * <p>
 * Mỗi kênh là một class {@code @Component} cài interface này. Spring tự gom tất cả vào {@code List<NotifyChannel>}
 * của {@link Notifier}, nên thêm kênh mới không phải sửa file nào khác.
 * <p>
 * Kênh không giữ cấu hình: mỗi lần gửi nhận {@code config} (token, người nhận…) của workspace đang gửi.
 */
public interface NotifyChannel {
    /** Mã kênh, viết thường: "telegram", "email"… */
    String type();

    /** Tên hiện trên giao diện: "Telegram", "Gmail"… */
    String label();

    /**
     * Gửi cho mọi người nhận trong cấu hình. Không ném lỗi khi gửi hỏng: lỗi từng người nằm trong kết quả,
     * để một người lỗi không làm mất tin của người khác.
     */
    SendResult send(Notice notice, JsonNode config);
}
