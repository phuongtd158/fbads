package com.fbads.schedule;

import com.fbads.common.JsNumber;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Body của POST /api/schedules. Chỉ mang dữ liệu; luật kiểm tra (nhiều trường liên quan nhau) nằm ở ScheduleValidator.
 * Trường số dùng @JsNumber để "20" và 20 như nhau, như bản Node.
 */
public record ScheduleRequest(
        String id,
        String name,
        String action,
        /** Các giờ chạy "HH:MM"; bản cũ gửi 1 giờ ở `time` */
        List<String> times,
        String time,
        /** Lịch khung giờ: bật lúc on, tắt lúc off */
        Window window,
        /** 0 = Chủ nhật … 6 = thứ Bảy */
        @JsonDeserialize(contentUsing = JsNumber.Deserializer.class) List<Double> days,
        /** "list" = danh sách cố định, "filter" = lọc lại mỗi lần chạy */
        String targetMode,
        List<String> targets,
        Filter filter,
        List<String> exclude,
        /** Đổi ngân sách: "percent" | "set" | "add" */
        String mode,
        @JsNumber Double value,
        @JsNumber Double max,
        @JsNumber Double min,
        /** Chỉ false mới tắt lịch; không gửi = bật */
        Boolean enabled) {

    public static final ScheduleRequest EMPTY = new ScheduleRequest(null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);

    public record Window(String on, String off) {}

    /** Lọc theo điều kiện: cấp, ngân sách so với x (và y khi "between"), cụm tên, trạng thái, tài khoản */
    public record Filter(String level, String op, @JsNumber Double x, @JsNumber Double y, String name, String status,
            String account, Boolean onlyRunning) {
        public static final Filter EMPTY = new Filter(null, null, null, null, null, null, null, null);
    }
}
