package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Cách gộp các điều kiện của rule: ALL = VÀ (mặc định), ANY = HOẶC. */
public enum MatchMode implements CodedEnum {
    ALL("all"),
    ANY("any");

    private final String code;

    MatchMode(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** Mã → hằng; trống hoặc mã lạ → null */
    @JsonCreator
    public static MatchMode from(String code) { return CodedEnum.fromCode(MatchMode.class, code); }

    /** Lưu vào DB bằng mã */
    public static class Converter extends CodedEnum.Converter<MatchMode> {
        public Converter() { super(MatchMode.class); }
    }
}
