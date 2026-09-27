package com.fbads.dto;

import java.util.List;

/** Kết quả lưu một lịch/rule: mục đã lưu + cảnh báo (vẫn lưu nhưng nên xem lại) */
public record Saved<T>(T item, List<String> warnings) {}
