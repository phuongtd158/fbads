package com.fbads.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Kết quả kiểm tra: lỗi theo từng trường (giữ thứ tự), cảnh báo, và giá trị đã chuẩn hoá. */
public record Result<T>(Map<String, String> errors, List<String> warnings, T value) {
    public boolean ok() { return errors.isEmpty(); }

    /** Lỗi đầu tiên (hiện trên thông báo chung của giao diện) */
    public String first() { return errors.isEmpty() ? "" : errors.values().iterator().next(); }

    /** Bộ gom lỗi/cảnh báo: chỉ giữ lỗi ĐẦU TIÊN của mỗi trường... trừ khi ghi đè có chủ ý (như `e.x = …` trong JS) */
    public static final class Collector {
        final Map<String, String> e = new LinkedHashMap<>();
        final List<String> w = new ArrayList<>();

        public void err(String field, String msg) { e.put(field, msg); }

        public boolean has(String field) { return e.containsKey(field); }

        public void warn(String msg) { w.add(msg); }

        public boolean empty() { return e.isEmpty(); }

        public <T> Result<T> done(T value) { return new Result<>(e, w, value); }
    }
}
