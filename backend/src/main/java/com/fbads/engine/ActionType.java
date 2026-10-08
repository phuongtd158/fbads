package com.fbads.engine;

/** Loại hành động lên một camp/nhóm QC. code = chữ ghi vào nhật ký (action.type), giữ như bản Node. */
public enum ActionType {
    ON("on"), OFF("off"), BUDGET("budget"), NOTIFY("notify");

    private final String code;

    ActionType(String code) { this.code = code; }

    public String code() { return code; }

    /** "on" | "off" | "budget" | "notify" → hằng; mã lạ → null */
    public static ActionType from(String code) {
        for (ActionType t : values()) if (t.code.equals(code)) return t;
        return null;
    }
}
