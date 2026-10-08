package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * JSON trả về của các API hay dùng. Mỗi record là một câu trả lời: tên trường của record = tên trường JSON, theo
 * đúng thứ tự. Trường null vẫn có trong JSON (giống bản Node). Muốn biết giao diện nhận được gì thì đọc file này.
 * <p>
 * ResponseShapeTest kiểm tra JSON giữ nguyên tên trường và kiểu giá trị.
 */
public final class Responses {
    private Responses() {}

    /**
     * Thân của mọi câu trả lời lỗi: { error } và, khi có, errors (lỗi từng ô nhập), drift (camp đã đổi kể từ lúc đó),
     * rateLimited (Facebook đang giới hạn số lần gọi). Trường không có thì không ghi.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String error, Map<String, String> errors, Boolean drift, Boolean rateLimited) {
        public static ApiError of(String error) { return new ApiError(error, null, null, null); }

        public static ApiError withErrors(String error, Map<String, String> errors) { return new ApiError(error, errors, null, null); }
    }

    /** { ok: true } */
    public record Ok(boolean ok) {
        public static final Ok OK = new Ok(true);
    }

    // ------------------------------------------------------------------ Cài đặt, sức khoẻ

    /** GET /api/health: ok = vòng tự động còn chạy; lastTickAt = lúc xong lượt gần nhất (ISO) */
    public record Health(boolean ok, String lastTickAt) {}
}
