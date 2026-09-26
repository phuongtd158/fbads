package com.fbads.web;

import com.fbads.common.ApiException;
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

import java.util.LinkedHashMap;
import java.util.Map;

/** Mọi lỗi từ controller → JSON { error, ... } như errorHandler của bản Node (giữ cờ drift / rateLimited cho giao diện). */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Kết quả kiểm tra không hợp lệ → 400 kèm lỗi từng trường */
    public static ResponseEntity<Map<String, Object>> bad(Result<?> r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", r.first());
        body.put("errors", r.errors());
        return ResponseEntity.badRequest().body(body);
    }

    public static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(new LinkedHashMap<>(Map.of("error", message)));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    /** Lỗi Bean Validation (@Valid trên request) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) errors.putIfAbsent(f.getField(), f.getDefaultMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errors.isEmpty() ? "Dữ liệu không hợp lệ" : errors.values().iterator().next());
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return error(400, "Dữ liệu gửi lên không đọc được (JSON không hợp lệ).");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> other(RuntimeException e) {
        if (!(e instanceof FbException)) log.error("Lỗi API", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        if (FbException.isRateLimited(e)) body.put("rateLimited", true);
        return ResponseEntity.status(500).body(body);
    }
}
