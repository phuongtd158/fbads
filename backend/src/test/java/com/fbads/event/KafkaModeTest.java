package com.fbads.event;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Chỉ "true" mới bật Kafka; mọi giá trị khác dùng Spring events, để lúc nào cũng có đúng một cách gửi sự kiện */
class KafkaModeTest {
    private static boolean on(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) env.setProperty(KafkaMode.PROPERTY, value);
        return KafkaMode.enabled(env);
    }

    @Test
    void onlyTrueEnablesKafka() {
        assertThat(on("true")).isTrue();
        assertThat(on(" TRUE ")).isTrue();
        assertThat(on(null)).isFalse();
        assertThat(on("false")).isFalse();
        assertThat(on("")).isFalse();
        assertThat(on("yes")).isFalse();
        assertThat(on("1")).isFalse();
    }
}
