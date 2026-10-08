package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Nguồn của một dòng nhật ký (và của một lần thực thi). */
public enum LogKind implements CodedEnum {
    MANUAL("manual"),
    SCHEDULE("schedule"),
    RULE("rule"),
    SYSTEM("system"),
    UNDO("undo"),
    COMPANY("company");

    private final String code;

    LogKind(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** Mã → hằng; trống hoặc mã lạ → null */
    @JsonCreator
    public static LogKind from(String code) { return CodedEnum.fromCode(LogKind.class, code); }

    /** Lưu vào DB bằng mã */
    public static class Converter extends CodedEnum.Converter<LogKind> {
        public Converter() { super(LogKind.class); }
    }
}
