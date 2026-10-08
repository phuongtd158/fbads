package com.fbads.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.common.CodedEnum;

/** Khoảng số liệu một rule xét (và khoảng so sánh trong điều kiện): mã gửi Facebook, nhãn tiếng Việt, số ngày. */
public enum RuleRange implements CodedEnum {
    TODAY("today", "hôm nay", 1),
    YESTERDAY("yesterday", "hôm qua", 1),
    LAST_3D("last_3d", "3 ngày gần nhất", 3),
    LAST_7D("last_7d", "7 ngày gần nhất", 7);

    private final String code;
    private final String label;
    private final int days;

    RuleRange(String code, String label, int days) {
        this.code = code;
        this.label = label;
        this.days = days;
    }

    @Override
    @JsonValue
    public String code() { return code; }

    public String label() { return label; }

    /** Số ngày của khoảng: so hai khoảng khác độ dài thì số liệu dạng tổng chia trung bình theo ngày */
    public int days() { return days; }

    /** Mã → hằng; trống hoặc mã lạ → null */
    @JsonCreator
    public static RuleRange from(String code) { return CodedEnum.fromCode(RuleRange.class, code); }

    /** Nhãn của một mã (mã lạ → chính mã đó, như bản Node) */
    public static String labelOf(String code) {
        RuleRange r = from(code);
        return r != null ? r.label : String.valueOf(code);
    }

    /** Số ngày của một mã (mã lạ → 1) */
    public static int daysOf(String code) {
        RuleRange r = from(code);
        return r != null ? r.days : 1;
    }

    /** Lưu vào DB bằng mã */
    public static class Converter extends CodedEnum.Converter<RuleRange> {
        public Converter() { super(RuleRange.class); }
    }
}
