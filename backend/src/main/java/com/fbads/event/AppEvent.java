package com.fbads.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fbads.security.WorkspaceContext;
import tools.jackson.databind.JsonNode;

/**
 * Một sự kiện trong tool: "vừa ghi nhật ký", "camp vừa đổi", "xong một lượt tự động"…
 * Phát một lần, nhiều nơi nhận độc lập: thông báo (Telegram, Gmail…), WebSocket (giao diện), thống kê theo ngày.
 * KAFKA_ENABLED=true thì đi qua Kafka (topic {@value EventTopics#EVENTS}), không thì qua Spring events trong ứng dụng.
 *
 * @param id     mã duy nhất (UUID): consumer dùng để bỏ qua bản nhận trùng (Kafka giao "ít nhất một lần")
 * @param type   loại sự kiện, xem các hằng bên dưới
 * @param key    khoá phân vùng Kafka: cùng khoá → cùng partition → nhận đúng thứ tự (vd. sự kiện nhật ký của workspace
 * 1 dùng khoá "1:logs")
 * @param at     thời điểm phát (epoch ms)
 * @param shouldNotify true = gửi thông báo cho sự kiện này. Trong JSON tên là "notify" (tên cũ "telegram" vẫn đọc được).
 *                     Không đặt tên component là notify được vì trùng Object.notify().
 * @param data   nội dung (dòng nhật ký, {id} của camp…), đúng dạng JSON mà giao diện đang nhận
 * @param workspace workspace phát ra sự kiện: consumer xử lý trong workspace này (kênh thông báo, múi giờ, thống kê…)
 *                  và giao diện chỉ người của workspace này nhận. null (sự kiện cũ, trước khi có workspace) = workspace 1.
 */
public record AppEvent(String id, String type, String key, long at,
                       @JsonProperty("notify") @JsonAlias("telegram") boolean shouldNotify, JsonNode data, Long workspace) {
    public AppEvent(String id, String type, String key, long at, boolean shouldNotify, JsonNode data) {
        this(id, type, key, at, shouldNotify, data, null);
    }

    public long workspaceId() { return workspace == null ? WorkspaceContext.DEFAULT : workspace; }

    /** Chạy việc của consumer trong workspace của sự kiện */
    public void runInWorkspace(Runnable r) { WorkspaceContext.run(workspaceId(), r); }

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
    /** Thông báo soạn sẵn (cảnh báo, báo cáo tuần, báo cáo công ty…): data = {topic, text}, xem EventBus.notify */
    public static final String NOTICE = "notice";
    /** Tên cũ của NOTICE (data = {text}): chỉ còn để đọc bản ghi cũ trong Kafka */
    public static final String LEGACY_TELEGRAM_TEXT = "telegram.text";
}
