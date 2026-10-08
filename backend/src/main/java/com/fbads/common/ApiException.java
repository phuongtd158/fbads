package com.fbads.common;

import java.util.Map;

/**
 * Lỗi trả về giao diện { error } với mã HTTP cho trước. Có thể kèm lỗi từng ô nhập (errors), cờ drift (camp đã đổi từ
 * lúc đó, hoàn tác cần hỏi lại) hoặc rateLimited (Facebook đang giới hạn số lần gọi). Xem dto/Responses.ApiError.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private Map<String, String> errors;
    private boolean drift;
    private boolean rateLimited;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Lỗi của một ô nhập: giao diện hiện ngay dưới ô đó */
    public ApiException withFieldError(String field, String message) {
        this.errors = Map.of(field, message);
        return this;
    }

    public ApiException withDrift() {
        this.drift = true;
        return this;
    }

    public ApiException withRateLimited() {
        this.rateLimited = true;
        return this;
    }

    public int status() { return status; }

    public Map<String, String> errors() { return errors; }

    public boolean drift() { return drift; }

    public boolean rateLimited() { return rateLimited; }
}
