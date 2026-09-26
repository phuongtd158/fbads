package com.fbads.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Số trong JSON trả về giống JavaScript: 500000.0 → 500000, 2.6775E7 → 26775000, NaN/Infinity → null.
 * Spring Boot tự gắn mọi bean JacksonModule vào JsonMapper dùng cho controller.
 */
@Configuration
public class JacksonConfig {
    static final class JsNumber extends ValueSerializer<Double> {
        @Override
        public void serialize(Double v, JsonGenerator gen, SerializationContext ctx) {
            if (v == null || v.isNaN() || v.isInfinite()) gen.writeNull();
            else if (v == Math.rint(v) && Math.abs(v) < 1e15) gen.writeNumber(v.longValue());
            else gen.writeNumber(v);
        }
    }

    @Bean
    SimpleModule jsNumbers() {
        SimpleModule m = new SimpleModule("js-numbers");
        m.addSerializer(Double.class, new JsNumber());
        m.addSerializer(double.class, new JsNumber());
        return m;
    }
}
