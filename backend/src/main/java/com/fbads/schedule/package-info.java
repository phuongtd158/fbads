/**
 * Lịch tự động: bật/tắt hoặc đổi ngân sách camp theo giờ, theo bộ lọc.
 * <p>
 * Đọc theo thứ tự: ScheduleController (API) → ScheduleService (lưu, xoá, chạy ngay) → ScheduleValidator (kiểm tra
 * dữ liệu gửi lên) → Schedule (bảng schedules, cùng ScheduleFilter, ScheduleWindow, ScheduleAction) → ScheduleRunner
 * (engine gọi mỗi lượt, chọn camp bằng BulkFilter rồi giao ActionExecutor làm).
 */
package com.fbads.schedule;
