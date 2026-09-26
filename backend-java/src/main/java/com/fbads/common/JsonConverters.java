package com.fbads.common;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Chuyển trường Java ↔ cột JSON của MariaDB. Mỗi kiểu dữ liệu có một converter riêng (JPA cần lớp cụ thể).
 * Dùng JsonMapper riêng (không phải bean của Spring) vì Hibernate tạo converter trước khi có context.
 */
public final class JsonConverters {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonConverters() {}

    abstract static class Base<T> implements AttributeConverter<T, String> {
        private final TypeReference<T> type;

        Base(TypeReference<T> type) { this.type = type; }

        @Override
        public String convertToDatabaseColumn(T value) {
            return value == null ? null : MAPPER.writeValueAsString(value);
        }

        @Override
        public T convertToEntityAttribute(String json) {
            return json == null || json.isBlank() ? null : MAPPER.readValue(json, type);
        }
    }

    public static class StringList extends Base<List<String>> {
        public StringList() { super(new TypeReference<>() {}); }
    }

    public static class IntList extends Base<List<Integer>> {
        public IntList() { super(new TypeReference<>() {}); }
    }

    /** Object JSON bất kỳ (giữ nguyên khoá có/không có, như dữ liệu của bản Node) */
    public static class AnyMap extends Base<Map<String, Object>> {
        public AnyMap() { super(new TypeReference<>() {}); }
    }

    public static class MapList extends Base<List<Map<String, Object>>> {
        public MapList() { super(new TypeReference<>() {}); }
    }

    /** { [mã tài khoản]: { cpa, roas, dailySpendLimit } } */
    public static class TargetsMap extends Base<Map<String, Map<String, Number>>> {
        public TargetsMap() { super(new TypeReference<>() {}); }
    }
}
