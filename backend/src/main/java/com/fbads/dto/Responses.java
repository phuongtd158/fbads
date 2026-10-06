package com.fbads.dto;

import com.fbads.company.CompanyApi;
import com.fbads.entity.CompanyReport;
import com.fbads.entity.LogEntry;
import com.fbads.entity.Rule;
import com.fbads.entity.Schedule;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * JSON trả về của các API hay dùng. Mỗi record là một câu trả lời: tên trường của record = tên trường JSON, theo
 * đúng thứ tự. Trường null vẫn có trong JSON (giống bản Node). Muốn biết giao diện nhận được gì thì đọc file này.
 * <p>
 * ResponseShapeTest kiểm tra JSON giữ nguyên tên trường và kiểu giá trị.
 */
public final class Responses {
    private Responses() {}

    /** { ok: true } */
    public record Ok(boolean ok) {
        public static final Ok OK = new Ok(true);
    }

    // ------------------------------------------------------------------ Camp / nhóm QC (ObjectsController)

    /** Mức dùng API Facebook: phần trăm và loại giới hạn */
    public record Usage(double pct, String tier) {}

    /**
     * Thông tin kèm danh sách camp: số liệu lúc nào (at), có phải số cũ không (stale), bị chặn tới khi nào
     * (blockedUntil), mức dùng API, tài khoản và lỗi từng tài khoản
     */
    public record ObjectsMeta(Long at, boolean stale, Long blockedUntil, Usage usage,
            List<Map<String, Object>> accounts, List<Map<String, Object>> accountErrors) {}

    /** GET /api/objects: camp + nhóm QC kèm số hôm nay, và các trường của ObjectsMeta */
    public record ObjectsList(List<AdObject> items, Long at, boolean stale, Long blockedUntil, Usage usage,
            List<Map<String, Object>> accounts, List<Map<String, Object>> accountErrors) {
        public ObjectsList(List<AdObject> items, ObjectsMeta m) {
            this(items, m.at(), m.stale(), m.blockedUntil(), m.usage(), m.accounts(), m.accountErrors());
        }
    }

    /** GET /api/insights: số liệu theo khoảng ngày, metrics = { [id camp/nhóm QC]: số liệu } */
    public record Insights(Map<String, Object> range, String key, String since, String until, Integer days, Long at,
            boolean stale, Long blockedUntil, Usage usage, List<Map<String, Object>> accountErrors,
            Map<String, Metrics> metrics) {}

    /** Một lần bật / tắt / đổi ngân sách (lấy từ Nhật ký) để đánh dấu trên biểu đồ xu hướng */
    public record TrendEvent(String ts, String date, String type, String source, String detail) {}

    /** GET /api/objects/{id}/trend: days = [{ date, spend, … }] */
    public record Trend(String id, String since, String until, List<Map<String, Object>> days,
            List<TrendEvent> events, Long at, boolean stale, Long blockedUntil) {}

    /** POST /api/logs/{id}/undo */
    public record Undone(boolean ok, LogEntry entry) {}

    // ------------------------------------------------------------------ Cài đặt (SettingsController)

    /** Nơi lưu dữ liệu (Cài đặt → Chung). Bản Java luôn lưu MySQL nên các trường còn lại cố định. */
    public record Storage(String mode, String provider, Long lastSavedAt, String lastError, boolean pending) {
        public static final Storage MYSQL = new Storage("db", "MySQL", null, "", false);
    }

    /** GET /api/state: mọi thứ giao diện cần lúc mở */
    public record State(ObjectNode settings, List<Schedule> schedules, List<Rule> rules, Storage storage) {}

    // ------------------------------------------------------------------ Báo cáo công ty (CompanyController)

    /** config = cài đặt đã bỏ mật khẩu (CompanyConfig.publicView) */
    public record CompanyOverview(Map<String, Object> config, List<CompanyReport> reports) {}

    public record CompanyConfigSaved(Map<String, Object> config) {}

    /** Đăng nhập thử: người dùng trên hệ thống công ty + các Team */
    public record CompanyLogin(Map<String, String> user, List<CompanyApi.Team> teams) {}

    public record CompanyTeams(List<CompanyApi.Team> teams) {}

    /** Tạo báo cáo ngay: reports = mọi bản báo cáo, built = id các bản vừa tạo / làm mới */
    public record CompanyBuilt(List<CompanyReport> reports, List<String> built) {}

    public record CompanySynced(int synced, List<CompanyReport> reports) {}
}
