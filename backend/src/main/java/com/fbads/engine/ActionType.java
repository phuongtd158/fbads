package com.fbads.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Loại hành động lên một camp/nhóm QC. code = chữ ghi vào nhật ký (action.type), giữ như bản Node. */
public enum ActionType implements CodedEnum {
    ON("on"), OFF("off"), BUDGET("budget"), NOTIFY("notify");

    private final String code;

    ActionType(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** "on" | "off" | "budget" | "notify" → hằng; mã lạ → null */
    @JsonCreator
    public static ActionType from(String code) { return CodedEnum.fromCode(ActionType.class, code); }
}
