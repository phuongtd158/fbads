package com.fbads.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka không chạy lúc khởi động: báo tiếng Việt kèm địa chỉ Kafka và cách sửa; lỗi khác không đụng tới */
class KafkaStartupFailureTest {
    private final KafkaStartupFailure analyzer =
            new KafkaStartupFailure(new MockEnvironment().withProperty("spring.kafka.bootstrap-servers", "localhost:9094"));

    @Test
    void explainsKafkaNotRunning() {
        Exception failure = new IllegalStateException("Could not configure topics", new TimeoutException());

        FailureAnalysis analysis = analyzer.analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("KAFKA_ENABLED=true", "localhost:9094");
        assertThat(analysis.getAction()).contains("KAFKA_ENABLED=false", "docker run");
    }

    @Test
    void ignoresOtherErrors() {
        assertThat(analyzer.analyze(new IllegalStateException("something else"))).isNull();
    }
}
