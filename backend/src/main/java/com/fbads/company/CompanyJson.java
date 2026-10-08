package com.fbads.company;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fbads.account.User;

import java.util.Map;

/**
 * Hình dạng JSON gửi đi / nhận về từ hệ thống báo cáo của công ty (xem CompanyApi), khai báo thành record để Jackson
 * đọc/ghi thẳng thay vì đi lần từng khoá. Trường chữ thiếu thì đổi thành "" như bản Node; trường "có thể không có"
 * (lần sửa, mốc, người gửi) giữ null.
 */
public final class CompanyJson {
    private CompanyJson() {}

    private static String text(String s) { return s == null ? "" : s; }

    /** Thân lỗi { error } của mọi câu trả lời lỗi */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorBody(String error) {
        public ErrorBody {
            error = text(error);
        }
    }

    /** Trả lời của POST /api/auth/login (cookie phiên nằm ở header, không ở đây) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(String csrf, User user) {
        public LoginResponse {
            csrf = text(csrf);
        }
    }

    /** Tài khoản công ty đang đăng nhập. id có thể là số hoặc chữ (Jackson đổi về chữ); null = không biết */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String id, String name, String email, String role, @JsonProperty("must_change") Boolean mustChange) {
        public User {
            name = text(name);
            email = text(email);
            role = text(role);
        }

        /** Công ty bắt đổi mật khẩu trước khi dùng tiếp */
        public boolean mustChangePassword() { return Boolean.TRUE.equals(mustChange); }
    }

    /**
     * Một báo cáo trên hệ thống công ty (GET /api/reports, và câu trả lời của POST /api/reports).
     * metrics: khoá như CompanyRules.METRIC_KEYS; null = công ty không gửi kèm số liệu.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteReport(String id, @JsonProperty("team_id") String teamId, @JsonProperty("user_id") String userId,
            String date, Double slot, String status, Integer revision, Boolean locked, Map<String, Double> metrics,
            String notes, String issue, String resolution, @JsonProperty("updated_at") String updatedAt) {
        public RemoteReport {
            id = text(id);
            teamId = text(teamId);
            date = text(date);
            status = text(status);
            notes = text(notes);
            issue = text(issue);
            resolution = text(resolution);
            updatedAt = text(updatedAt);
        }

        /** Báo cáo của đúng ngày (YYYY-MM-DD) và mốc giờ này không */
        public boolean isFor(String day, int slotHour) { return day.equals(date) && slot != null && slot == slotHour; }

        public boolean isLocked() { return Boolean.TRUE.equals(locked); }

        /** Bản sao với ngày cắt còn YYYY-MM-DD (công ty có thể trả kèm giờ) */
        RemoteReport withDayOnly() {
            String d = date.length() > 10 ? date.substring(0, 10) : date;
            return new RemoteReport(id, teamId, userId, d, slot, status, revision, locked, metrics, notes, issue, resolution,
                    updatedAt);
        }

        /**
         * Phần công ty trả về đè lên bản này (như Object.assign của bản Node): trường nào công ty có gửi thì lấy của
         * công ty. other = null → giữ nguyên.
         */
        RemoteReport overlay(RemoteReport other) {
            if (other == null) return this;
            return new RemoteReport(pick(other.id, id), pick(other.teamId, teamId), other.userId != null ? other.userId : userId,
                    pick(other.date, date), other.slot != null ? other.slot : slot, pick(other.status, status),
                    other.revision != null ? other.revision : revision, other.locked != null ? other.locked : locked,
                    other.metrics != null ? other.metrics : metrics, pick(other.notes, notes), pick(other.issue, issue),
                    pick(other.resolution, resolution), pick(other.updatedAt, updatedAt));
        }

        private static String pick(String mine, String fallback) { return mine.isEmpty() ? fallback : mine; }
    }

    /** Thân POST /api/reports để gửi báo cáo mới */
    @JsonPropertyOrder({"team_id", "date", "slot", "metrics", "notes", "issue", "resolution"})
    public record NewReport(@JsonProperty("team_id") String teamId, String date, int slot, Map<String, Long> metrics, String notes,
            String issue, String resolution) {}

    /** Thân POST /api/reports để cập nhật báo cáo đã có: như gửi mới, kèm lần sửa đang có trên công ty và lý do */
    @JsonPropertyOrder({"team_id", "date", "slot", "metrics", "notes", "issue", "resolution", "revision", "reason"})
    public record ReportUpdate(@JsonProperty("team_id") String teamId, String date, int slot, Map<String, Long> metrics,
            String notes, String issue, String resolution, Integer revision, String reason) {}
}
