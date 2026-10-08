package com.fbads.ads;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/** GET /api/objects/{id}/trend: days = [{ date, spend, … }] */
public record Trend(String id, String since, String until, List<TrendDay> days,
        List<TrendEvent> events, Long at, boolean stale, Long blockedUntil) {
    /** Một lần bật / tắt / đổi ngân sách (lấy từ Nhật ký) để đánh dấu trên biểu đồ xu hướng */
    public record TrendEvent(String ts, String date, String type, String source, String detail) {}

    /** Số liệu của một ngày trên biểu đồ xu hướng: { date, spend, impressions, … } (các trường của Metrics nằm cùng cấp) */
    public record TrendDay(String date, @JsonUnwrapped Metrics metrics) {}
}
