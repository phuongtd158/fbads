package com.fbads.event;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka không chạy: nơi phát sự kiện không phải chờ (trước đây mỗi sự kiện chặn luồng gọi tới max.block.ms) */
class KafkaEventSenderTest {
    @Test
    void publishingNeverWaitsForKafka() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        DefaultKafkaProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1", // không có Kafka ở đây
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        KafkaEventSender sender = new KafkaEventSender(new KafkaTemplate<>(pf), json);
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                sender.send(new AppEvent("e" + i, AppEvent.OBJECTS_CHANGED, "k", 0, false, json.valueToTree(Map.of("id", "x"))));
            }
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(300);
        } finally {
            sender.destroy();
            pf.destroy();
        }
    }
}
