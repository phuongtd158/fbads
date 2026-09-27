-- Thống kê số thao tác mỗi ngày theo nguồn (schedule | rule | manual | undo | system).
-- Consumer "thống kê" (service/EventStatsService) ghi từ sự kiện log.created: qua Kafka topic fbads.events,
-- hoặc qua Spring events khi tắt Kafka.
CREATE TABLE event_stats (
    day     VARCHAR(10) NOT NULL,
    source  VARCHAR(20) NOT NULL,
    actions INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (day, source)
);

-- Sự kiện đã đếm. Kafka giao "ít nhất một lần" nên cùng một sự kiện có thể tới 2 lần: có mã ở đây rồi thì không đếm nữa.
-- Vòng tự động xoá các dòng cũ hơn 7 ngày.
CREATE TABLE event_stats_seen (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    day      VARCHAR(10) NOT NULL,
    INDEX event_stats_seen_day (day)
);
