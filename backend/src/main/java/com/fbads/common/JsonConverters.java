package com.fbads.common;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Chuyển trường Java ↔ cột JSON của MySQL. Mỗi kiểu dữ liệu có một converter riêng (JPA cần lớp cụ thể).
 * Dùng JsonMapper riêng (không phải bean của Spring) vì Hibernate tạo converter trước khi có context.
 */
public final class JsonConverters {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonConverters() {}

    abstract static class Base<T> implements AttributeConverter<T, String> {
        private final JavaType type;

        Base(TypeReference<T> type) { this.type = MAPPER.getTypeFactory().constructType(type); }

        Base(Class<T> type) { this.type = MAPPER.getTypeFactory().constructType(type); }

        @Override
        public String convertToDatabaseColumn(T value) {
            return value == null ? null : MAPPER.writeValueAsString(value);
        }

        @Override
        public T convertToEntityAttribute(String json) {
            return json == null || json.isBlank() ? null : MAPPER.readValue(json, type);
        }
    }

    /** Một record bất kỳ ↔ cột JSON. Mỗi record có một lớp con nhỏ (JPA cần lớp cụ thể), vd LogTarget.Converter. */
    public abstract static class Of<T> extends Base<T> {
        protected Of(Class<T> type) {
            super(type);
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

    /** { khoá: số nguyên hoặc null } (null = chưa nhập), giữ thứ tự khoá */
    public static class NullableLongMap extends Base<Map<String, Long>> {
        public NullableLongMap() { super(new TypeReference<>() {}); } // Jackson đọc Map thành LinkedHashMap: giữ thứ tự khoá
    }

    public static class MapList extends Base<List<Map<String, Object>>> {
        public MapList() { super(new TypeReference<>() {}); }
    }

    /** { [mã tài khoản]: { cpa, roas, dailySpendLimit } } */
    public static class TargetsMap extends Base<Map<String, Map<String, Number>>> {
        public TargetsMap() { super(new TypeReference<>() {}); }
    }
}
