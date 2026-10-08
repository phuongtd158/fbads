package com.fbads.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * Kết quả lưu một lịch/rule: mục đã lưu + cảnh báo (vẫn lưu nhưng nên xem lại).
 * JSON: các trường của mục đã lưu nằm ngay cấp ngoài, thêm warnings ({ ...mục, warnings } như bản Node).
 */
public record Saved<T>(@JsonUnwrapped T item, List<String> warnings) {}
