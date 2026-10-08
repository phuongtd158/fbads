package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Cấp quảng cáo: chiến dịch hoặc nhóm quảng cáo. */
public enum AdLevel implements CodedEnum {
    CAMPAIGN("campaign"),
    ADSET("adset");

    private final String code;

    AdLevel(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** Mã → hằng; trống hoặc mã lạ → null */
    @JsonCreator
    public static AdLevel from(String code) { return CodedEnum.fromCode(AdLevel.class, code); }

    /** Lưu vào DB bằng mã */
    public static class Converter extends CodedEnum.Converter<AdLevel> {
        public Converter() { super(AdLevel.class); }
    }
}
