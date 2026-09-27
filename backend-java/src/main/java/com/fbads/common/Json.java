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

    /** String(v ?? '') */
    public static String str(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return "";
        if (n.isString()) return n.stringValue();
        if (n.isNumber()) return Fmt.num(n.doubleValue());
        if (n.isBoolean()) return Boolean.toString(n.booleanValue());
        return n.toString();
    }

    /** Có khoá này trong object không (hasOwnProperty) */
    public static boolean has(JsonNode obj, String key) {
        return obj != null && obj.isObject() && obj.has(key);
    }

    /** Giá trị "truthy" của JS */
    public static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return false;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isNumber()) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (n.isString()) return !n.stringValue().isEmpty();
        return true;
    }

    /** Mảng → danh sách chuỗi; không phải mảng → danh sách rỗng */
    public static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) for (JsonNode x : n) out.add(str(x));
        return out;
    }
}
