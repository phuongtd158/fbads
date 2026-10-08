package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Hành động của lịch: bật, tắt, đổi ngân sách, hoặc khung giờ (bật lúc on, tắt lúc off). */
public enum ScheduleAction implements CodedEnum {
    ON("on"),
    OFF("off"),
    BUDGET("budget"),
    WINDOW("window");

    private final String code;

    ScheduleAction(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** Mã → hằng; trống hoặc mã lạ → null */
    @JsonCreator
    public static ScheduleAction from(String code) { return CodedEnum.fromCode(ScheduleAction.class, code); }

    /** Lưu vào DB bằng mã */
    public static class Converter extends CodedEnum.Converter<ScheduleAction> {
        public Converter() { super(ScheduleAction.class); }
    }
}
