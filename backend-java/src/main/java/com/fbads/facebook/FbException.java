package com.fbads.facebook;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lỗi khi gọi Facebook: message đã dịch sang tiếng Việt; fb = chi tiết gốc (mã lỗi, fbtrace_id…) để ghi nhật ký. */
public class FbException extends RuntimeException {
    private static final java.util.Set<Integer> RATE_CODES = java.util.Set.of(4, 17, 32, 613);
    private final Map<String, Object> fb;

    public FbException(String message, Map<String, Object> fb) {
        super(message);
        this.fb = fb == null ? new LinkedHashMap<>() : fb;
    }

    public Map<String, Object> fb() { return fb; }

    public Integer code() { return fb.get("code") instanceof Number n ? n.intValue() : null; }

    public boolean isRateLimit() { return code() != null && isRateLimitCode(code()); }

    public static boolean isRateLimitCode(int c) { return RATE_CODES.contains(c) || (c >= 80000 && c <= 80014); }

    public static boolean isRateLimited(Throwable e) { return e instanceof FbException f && f.isRateLimit(); }

    /** Lỗi bất kỳ → object gọn để lưu nhật ký (lỗi Facebook giữ nguyên mã; lỗi nội bộ kèm vài dòng stack) */
    public static Map<String, Object> describe(Throwable e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
        Map<String, Object> fb = e instanceof FbException f ? f.fb() : Map.of();
        out.putAll(fb);
        if (fb.get("code") == null && fb.get("network") == null && fb.get("httpStatus") == null) {
            StringBuilder sb = new StringBuilder(e.toString());
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(5, st.length); i++) sb.append("\n    at ").append(st[i]);
            out.put("stack", sb.length() > 800 ? sb.substring(0, 800) : sb.toString());
        }
        return out;
    }
}
