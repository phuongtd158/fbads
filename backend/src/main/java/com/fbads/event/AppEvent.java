package com.fbads.event;

import tools.jackson.databind.JsonNode;

/**
 * Một sự kiện trong tool: "vừa ghi nhật ký", "camp vừa đổi", "xong một lượt tự động"…
 * Phát một lần, nhiều nơi nhận độc lập: Telegram, WebSocket (giao diện), thống kê theo ngày.
 * KAFKA_ENABLED=true thì đi qua Kafka (topic {@value EventTopics#EVENTS}), không thì qua Spring events trong ứng dụng.
 *
 * @param id     mã duy nhất (UUID): consumer dùng để bỏ qua bản nhận trùng (Kafka giao "ít nhất một lần")
 * @param type   loại sự kiện, xem các hằng bên dưới
 * @param key    khoá phân vùng Kafka: cùng khoá → cùng partition → nhận đúng thứ tự (vd. mọi sự kiện nhật ký dùng khoá "logs")
 * @param at     thời điểm phát (epoch ms)
 * @param telegram true = gửi Telegram cho sự kiện này
 * @param data   nội dung (dòng nhật ký, {id} của camp…), đúng dạng JSON mà giao diện đang nhận
 */
public record AppEvent(String id, String type, String key, long at, boolean telegram, JsonNode data) {
    /** Dòng nhật ký mới (bật/tắt camp, đổi ngân sách, rule kích hoạt, chạy lịch, thao tác tay, hoàn tác…) */
    public static final String LOG_CREATED = "log.created";
    /** Dòng nhật ký đã có vừa được sửa (vd. đánh dấu đã hoàn tác) */
    public static final String LOG_UPDATED = "log.updated";
    /** Camp/nhóm QC vừa đổi trạng thái hoặc ngân sách: data = {id} */
    public static final String OBJECTS_CHANGED = "objects.changed";
    /** Xong một lượt tự động: data = {at} */
    public static final String ENGINE_TICK = "engine.tick";
    /** Báo cáo hằng ngày tới giờ gửi: data = {text} */
    public static final String DAILY_REPORT = "report.daily";
}
