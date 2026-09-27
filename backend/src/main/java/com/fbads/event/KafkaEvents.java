package com.fbads.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ContainerPostProcessor;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * KAFKA_ENABLED=true: sự kiện đi qua Kafka.
 *  - Producer: KafkaEventSender, mỗi sự kiện là một bản ghi JSON trên topic fbads.events, khoá = AppEvent.key.
 *  - Consumer: xem KafkaEventListeners (Telegram, WebSocket, thống kê), mỗi loại một consumer group riêng
 *    nên cùng một sự kiện được cả 3 nhận ("một sự kiện, nhiều nơi nhận").
 */
@Configuration
@Conditional(KafkaMode.On.class)
public class KafkaEvents {
    /**
     * Tạo topic lúc khởi động nếu chưa có (Kafka chưa chạy thì báo lỗi và dừng, xem spring.kafka.admin.fail-fast).
     * Kafka chỉ giữ thứ tự trong cùng một partition, tức cùng khoá: các sự kiện nhật ký dùng chung khoá "logs" (LogService).
     * Mỗi consumer hiện chạy một luồng, đọc cả 3 partition; 3 partition để sau này tăng số luồng/số bản tool khi cần.
     */
    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(EventTopics.EVENTS).partitions(EventTopics.PARTITIONS).replicas(1).build();
    }

    @Bean
    KafkaEventSender kafkaEventTransport(KafkaTemplate<String, String> kafka, JsonMapper json) {
        return new KafkaEventSender(kafka, json);
    }

    /**
     * Thay bộ ghi log mặc định của Spring Boot (ghi ERROR kèm nội dung bản ghi cho từng lần gửi lỗi):
     * KafkaEventSender tự ghi log gọn, không kèm nội dung.
     */
    @Bean
    ProducerListener<Object, Object> quietProducerListener() {
        return new ProducerListener<>() {};
    }

    /**
     * Consumer thống kê gặp lỗi (DB tạm mất kết nối…): thử lại 5 lần, mỗi lần cách 2 giây, rồi bỏ qua bản ghi đó.
     * Bản ghi không đọc được thì bỏ qua ngay, thử lại cũng vô ích.
     * Consumer Telegram có cơ chế riêng (@RetryableTopic, topic thử lại + DLT); consumer WebSocket tự bắt lỗi.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(2000L, 5));
        handler.addNotRetryableExceptions(KafkaEventListeners.BadEventException.class);
        return handler;
    }

    /**
     * Consumer WebSocket (fbads-live-…) không bao giờ lưu vị trí đã đọc: group của nó chỉ sống cùng một lần chạy,
     * không lưu thì Kafka xoá group ngay khi tool tắt, thay vì giữ group bỏ đi 7 ngày sau mỗi lần khởi động lại.
     */
    @Bean
    ContainerPostProcessor<Object, Object, AbstractMessageListenerContainer<Object, Object>> neverCommit() {
        return c -> c.getContainerProperties().setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
    }
}
