package com.fbads.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * KAFKA_ENABLED=true: sự kiện đi qua Kafka.
 *  - Producer: mỗi sự kiện là một bản ghi JSON trên topic fbads.events, khoá = AppEvent.key.
 *  - Consumer: xem KafkaEventListeners (Telegram, WebSocket, thống kê), mỗi loại một consumer group riêng
 *    nên cùng một sự kiện được cả 3 nhận ("một sự kiện, nhiều nơi nhận").
 */
@Configuration
@ConditionalOnProperty(name = "fbads.kafka.enabled", havingValue = "true")
public class KafkaEvents {
    private static final Logger log = LoggerFactory.getLogger(KafkaEvents.class);

    /** Tạo topic lúc khởi động nếu chưa có. 3 partition: sự kiện khác khoá được xử lý song song, cùng khoá vẫn đúng thứ tự. */
    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(EventTopics.EVENTS).partitions(3).replicas(1).build();
    }

    /**
     * Gửi bất đồng bộ: không chờ Kafka xác nhận. Lỗi gửi (Kafka không chạy…) chỉ ghi log;
     * producer chờ tối đa max.block.ms (application.yml) nên engine không bị treo lâu.
     */
    @Bean
    EventTransport kafkaEventTransport(KafkaTemplate<String, String> kafka, JsonMapper json) {
        return e -> kafka.send(EventTopics.EVENTS, e.key(), json.writeValueAsString(e)).whenComplete((r, ex) -> {
            if (ex != null) log.warn("Không gửi được sự kiện {} ({}) lên Kafka: {}", e.type(), e.id(), ex.getMessage());
        });
    }

    /**
     * Consumer WebSocket và thống kê gặp lỗi (DB tạm mất kết nối…): thử lại 5 lần, mỗi lần cách 2 giây, rồi bỏ qua bản ghi đó.
     * Consumer Telegram có cơ chế riêng (@RetryableTopic, topic thử lại + DLT).
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, 5));
    }
}
