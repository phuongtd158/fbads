package com.fbads.engine;

/** Cách đổi ngân sách: PERCENT = theo %, ADD = cộng/trừ số tiền, SET = đặt đúng số tiền. code = chữ ghi vào nhật ký. */
public enum BudgetMode {
    PERCENT("percent"), ADD("add"), SET("set");

    private final String code;

    BudgetMode(String code) { this.code = code; }

    public String code() { return code; }

    /** "percent" | "add" | "set" → hằng; mã lạ hoặc trống → null */
    public static BudgetMode from(String code) {
        for (BudgetMode m : values()) if (m.code.equals(code)) return m;
        return null;
    }
}
