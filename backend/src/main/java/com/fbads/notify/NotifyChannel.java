package com.fbads.notify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

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

    /** Các ô cấu hình, theo thứ tự hiện trên form */
    List<ConfigField> fields();

    /** Hướng dẫn lấy cấu hình (HTML ngắn, hiện dưới form), "" = không có */
    default String help() { return ""; }

    /** Loại tin kênh mới nhận nếu người dùng không chọn lại. Mặc định: mọi loại tin */
    default List<Notice.Topic> defaultTopics() { return List.of(Notice.Topic.values()); }

    /**
     * Kiểm tra cấu hình người dùng nhập trước khi lưu, và chuẩn hoá ngay trong {@code config} (bỏ khoảng trắng, bỏ trùng…).
     * Ô bí mật để trống khi sửa đã được điền lại giá trị cũ trước khi gọi.
     *
     * @return lỗi theo từng ô (khoá = key của ConfigField), rỗng = hợp lệ
     */
    Map<String, String> validate(ObjectNode config);

    /**
     * Gửi cho mọi người nhận trong cấu hình. Không ném lỗi khi gửi hỏng: lỗi từng người nằm trong kết quả,
     * để một người lỗi không làm mất tin của người khác.
     */
    SendResult send(Notice notice, JsonNode config);
}
