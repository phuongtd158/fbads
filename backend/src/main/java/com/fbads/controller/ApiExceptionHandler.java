package com.fbads.controller;

import com.fbads.common.ApiException;
import com.fbads.common.ValidationException;
import com.fbads.dto.Responses.ApiError;
import com.fbads.facebook.FbException;
import com.fbads.validation.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mọi lỗi từ controller → JSON { error, ... } như errorHandler của bản Node (giữ cờ drift / rateLimited cho giao diện). */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Kết quả kiểm tra không hợp lệ → 400 kèm lỗi từng trường */
    public static ResponseEntity<ApiError> bad(Result<?> r) {
        return ResponseEntity.badRequest().body(ApiError.withErrors(r.first(), r.errors()));
    }

    public static ResponseEntity<ApiError> error(int status, String message) {
        return ResponseEntity.status(status).body(ApiError.of(message));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.getMessage(), e.errors(), e.drift() ? true : null,
                e.rateLimited() ? true : null));
    }

    /** Service báo dữ liệu không hợp lệ (luật kiểm tra lịch/rule/cài đặt) */
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException e) {
        return bad(e.result());
    }

    /** Lỗi Bean Validation (@Valid trên request) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) errors.putIfAbsent(f.getField(), f.getDefaultMessage());
        String first = errors.isEmpty() ? "Dữ liệu không hợp lệ" : errors.values().iterator().next();
        return ResponseEntity.badRequest().body(ApiError.withErrors(first, errors));
    }

    /**
     * JSON hỏng → báo JSON không hợp lệ. JSON đúng nhưng sai kiểu so với DTO (vd. "on": "abc", "conditions": 5)
     * → 400 { error, errors: { trường: "Sai kiểu dữ liệu" } }; trường lồng ghi dạng conditions[0].value.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        if (!(e.getMostSpecificCause() instanceof MismatchedInputException m))
            return error(400, "Dữ liệu gửi lên không đọc được (JSON không hợp lệ).");
        String field = fieldPath(m);
        return ResponseEntity.badRequest().body(field.isEmpty() ? ApiError.withErrors(WRONG_TYPE, Map.of())
                : ApiError.withErrors(field + ": " + WRONG_TYPE, Map.of(field, WRONG_TYPE)));
    }

    static final String WRONG_TYPE = "Sai kiểu dữ liệu";

    /** Đường dẫn trường bị lỗi, vd. "conditions[0].value"; "" khi cả body sai kiểu (vd. gửi mảng thay vì object) */
    static String fieldPath(JacksonException e) {
        StringBuilder sb = new StringBuilder();
        for (JacksonException.Reference r : e.getPath()) {
            if (r.getPropertyName() != null) sb.append(sb.isEmpty() ? "" : ".").append(r.getPropertyName());
            else if (r.getIndex() >= 0) sb.append('[').append(r.getIndex()).append(']');
        }
        return sb.toString();
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError> other(RuntimeException e) {
        if (!(e instanceof FbException)) log.error("Lỗi API", e);
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return ResponseEntity.status(500).body(new ApiError(message, null, null, FbException.isRateLimited(e) ? true : null));
    }
}
