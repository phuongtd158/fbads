package com.fbads.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Thân của mọi câu trả lời lỗi: { error } và, khi có, errors (lỗi từng ô nhập), drift (camp đã đổi kể từ lúc đó),
 * rateLimited (Facebook đang giới hạn số lần gọi). Trường không có thì không ghi.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, Map<String, String> errors, Boolean drift, Boolean rateLimited) {
    public static ApiError of(String error) { return new ApiError(error, null, null, null); }

    public static ApiError withErrors(String error, Map<String, String> errors) { return new ApiError(error, errors, null, null); }
}
