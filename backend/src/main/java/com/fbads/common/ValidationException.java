package com.fbads.common;

import com.fbads.validation.Result;

/** Dữ liệu gửi lên không qua luật kiểm tra → ApiExceptionHandler trả 400 { error, errors: { trường: câu báo } } */
public class ValidationException extends RuntimeException {
    private final transient Result<?> result;

    public ValidationException(Result<?> result) {
        super(result.first());
        this.result = result;
    }

    public Result<?> result() { return result; }
}
