package com.fbads.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Lỗi khi gọi Facebook: message đã dịch sang tiếng Việt; fb = chi tiết gốc (mã lỗi, fbtrace_id…) để ghi nhật ký. */
public class FbException extends RuntimeException {
    private static final Set<Integer> RATE_CODES = Set.of(4, 17, 32, 613);
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
}
