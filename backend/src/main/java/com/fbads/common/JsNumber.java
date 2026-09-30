package com.fbads.common;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu trường số của request đọc theo kiểu Number() của JS, như shared/validate.mjs:
 * 12 hoặc "12" → 12.0; "abc" → NaN (luật kiểm tra báo lỗi đúng trường, không phải "Sai kiểu dữ liệu");
 * null / "" / không gửi → null (= ô trống, chưa nhập).
 * Dùng: {@code @JsNumber Double value} trong record request.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(using = JsNumber.Deserializer.class)
public @interface JsNumber {

    class Deserializer extends ValueDeserializer<Double> {
        @Override
        public Double deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonNode n = ctxt.readTree(p);
            return Json.isBlank(n) ? null : Json.num(n);
        }
    }
}
