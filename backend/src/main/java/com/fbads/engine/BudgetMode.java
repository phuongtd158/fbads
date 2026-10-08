package com.fbads.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Cách đổi ngân sách: PERCENT = theo %, ADD = cộng/trừ số tiền, SET = đặt đúng số tiền. code = chữ ghi vào nhật ký. */
public enum BudgetMode implements CodedEnum {
    PERCENT("percent"), ADD("add"), SET("set");

    private final String code;

    BudgetMode(String code) { this.code = code; }

    @Override
    @JsonValue
    public String code() { return code; }

    /** "percent" | "add" | "set" → hằng; mã lạ hoặc trống → null */
    @JsonCreator
    public static BudgetMode from(String code) { return CodedEnum.fromCode(BudgetMode.class, code); }
}
