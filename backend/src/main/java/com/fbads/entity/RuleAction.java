package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Hành động của rule. */
public enum RuleAction implements CodedEnum {
    PAUSE("pause"),
    INCREASE("increase"),
    DECREASE("decrease"),
    NOTIFY("notify"),
    LADDER("ladder");

    private final String code;

    RuleAction(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** Mã → hằng; trống hoặc mã lạ → null */
    @JsonCreator
    public static RuleAction from(String code) { return CodedEnum.fromCode(RuleAction.class, code); }

    /** Lưu vào DB bằng mã */
    public static class Converter extends CodedEnum.Converter<RuleAction> {
        public Converter() { super(RuleAction.class); }
    }
}
