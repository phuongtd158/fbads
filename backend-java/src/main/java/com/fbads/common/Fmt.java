package com.fbads.common;

import java.math.BigDecimal;
import java.util.Locale;

/** Định dạng số giống JavaScript để câu chữ trong nhật ký/Telegram y hệt bản Node. */
public final class Fmt {
    private Fmt() {}

    /** Math.round(n).toLocaleString('vi-VN'): 1234567 → "1.234.567" */
    public static String money(double n) {
        long v = Math.round(n);
        String digits = Long.toString(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) sb.append('.');
            sb.append(digits.charAt(i));
        }
        return (v < 0 ? "-" : "") + sb;
    }

    /** Số như JS in ra trong template string: 20 → "20", 1.5 → "1.5" */
    public static String num(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** Number(x).toFixed(2) */
    public static String fixed2(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
