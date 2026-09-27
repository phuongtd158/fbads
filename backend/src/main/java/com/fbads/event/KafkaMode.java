package com.fbads.event;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Bật Kafka khi fbads.kafka.enabled (KAFKA_ENABLED) = "true" (không phân biệt hoa thường, bỏ khoảng trắng thừa).
 * Mọi giá trị khác, kể cả để trống hay gõ sai, là tắt. On và Off luôn ngược nhau, nên lúc nào cũng có đúng một EventTransport.
 */
public final class KafkaMode {
    public static final String PROPERTY = "fbads.kafka.enabled";

    private KafkaMode() {}

    static boolean enabled(Environment env) {
        String v = env.getProperty(PROPERTY);
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    /** Kafka bật: KafkaEvents, KafkaEventListeners */
    public static class On implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) { return enabled(context.getEnvironment()); }
    }

    /** Kafka tắt: LocalEvents (Spring events) */
    public static class Off implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) { return !enabled(context.getEnvironment()); }
    }
}
