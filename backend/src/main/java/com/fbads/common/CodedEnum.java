package com.fbads.common;

import jakarta.persistence.AttributeConverter;

/**
 * Enum có "mã" chữ thường như bản Node ("pause", "adset", "today"…). Trong Java dùng hằng viết hoa (RuleAction.PAUSE),
 * còn JSON gửi giao diện và cột trong DB vẫn là mã cũ, nên dữ liệu không đổi.
 * <p>
 * Mỗi enum: khai báo {@code @JsonValue} trên code() để Jackson ghi mã, và một hàm {@code @JsonCreator from(String)} để đọc lại.
 */
public interface CodedEnum {
    String code();

    /** Tìm hằng theo mã; trống hoặc mã lạ → null */
    static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
        if (code == null || code.isEmpty()) return null;
        for (E e : type.getEnumConstants()) if (e.code().equals(code)) return e;
        return null;
    }

    /** Lưu enum vào cột chữ của DB bằng mã (không phải tên hằng). Mỗi enum có một lớp con nhỏ vì JPA cần lớp cụ thể. */
    abstract class Converter<E extends Enum<E> & CodedEnum> implements AttributeConverter<E, String> {
        private final Class<E> type;

        protected Converter(Class<E> type) { this.type = type; }

        @Override
        public String convertToDatabaseColumn(E value) { return value == null ? null : value.code(); }

        @Override
        public E convertToEntityAttribute(String code) { return fromCode(type, code); }
    }
}
