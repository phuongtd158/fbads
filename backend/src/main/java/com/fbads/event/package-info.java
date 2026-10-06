/**
 * Phát và nhận sự kiện. EventBus là nơi phát duy nhất; đi bằng Spring events (LocalEvents) hoặc Kafka
 * (KafkaEvents) tuỳ KAFKA_ENABLED.
 * <p>
 * Đọc trước: EventBus, LocalEvents, rồi KafkaEventListeners nếu muốn hiểu phần Kafka.
 */
package com.fbads.event;
