package com.fbads.event;

/**
 * Đưa sự kiện tới các consumer. Có 2 bản, chọn theo KAFKA_ENABLED:
 *  - KafkaEvents: gửi lên topic Kafka, consumer ở mọi bản tool đọc về;
 *  - LocalEvents: Spring events, gọi thẳng các consumer trong cùng ứng dụng.
 */
public interface EventTransport {
    void send(AppEvent event);
}
