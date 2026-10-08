/**
 * Phát và nhận sự kiện. EventBus là nơi phát duy nhất; đi bằng Spring events (LocalEvents) hoặc Kafka
 * (KafkaEvents) tuỳ KAFKA_ENABLED.
 * <p>
 * Nơi nhận: LiveEvents đẩy xuống trình duyệt qua WebSocket, EventStatsService đếm sự kiện (bảng event_stats),
 * notify/Notifier gửi thông báo.
 * <p>
 * Đọc trước: EventBus, LocalEvents, rồi KafkaEventListeners nếu muốn hiểu phần Kafka.
 */
package com.fbads.event;
