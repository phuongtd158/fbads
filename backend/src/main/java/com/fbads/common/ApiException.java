package com.fbads.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lỗi trả về giao diện dạng { error, ...extra } với mã HTTP cho trước. */
public class ApiException extends RuntimeException {
    private final int status;
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException with(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    public int status() { return status; }

    public Map<String, Object> extra() { return extra; }
}
