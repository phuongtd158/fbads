package com.fbads.common;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Đọc dữ liệu JSON "lỏng" từ trình duyệt theo đúng cách bản Node hiểu: số có thể gửi dạng chuỗi, ô trống = chưa nhập.
 * Tương đương các hàm num / isBlank / String(x ?? '') trong shared/validate.mjs.
 */
public final class Json {
    private Json() {}

    public static boolean isBlank(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isString() && n.stringValue().isEmpty());
    }

    /** Number(v) của JS: ô trống → NaN; "12" → 12; "abc" → NaN; true → 1 */
    public static double num(JsonNode n) {
        if (isBlank(n)) return Double.NaN;
        if (n.isNumber()) return n.doubleValue();
        if (n.isBoolean()) return n.booleanValue() ? 1 : 0;
        if (n.isString()) {
            String s = n.stringValue().trim();
            if (s.isEmpty()) return 0; // Number('  ') = 0
            try {
                if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
                double d = Double.parseDouble(s);
                // Double.parseDouble nhận "1d", "1f", "NaN" mà JS thì không
                if (s.matches(".*[dDfF]$") || s.equals("NaN")) return Double.NaN;
                return d;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    // ----- Hai hàm trên dùng khi Jackson đọc trường @JsNumber. Các hàm dưới dùng trên trường của DTO: null = không gửi / ô trống

    /** Trường @JsNumber: null (ô trống) → NaN */
    public static double num(Double d) { return d == null ? Double.NaN : d; }

    /** String(v ?? '') */
    public static String str(String s) { return s == null ? "" : s; }

    public static boolean truthy(String s) { return s != null && !s.isEmpty(); }

    /** Danh sách chuỗi, phần tử null → ""; không gửi → danh sách rỗng */
    public static List<String> strings(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list != null) for (String x : list) out.add(str(x));
        return out;
    }
}
