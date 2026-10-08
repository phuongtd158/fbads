package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fbads.company.CompanyApi;
import com.fbads.entity.AppSettings;
import com.fbads.entity.CompanyReport;
import com.fbads.entity.User;
import com.fbads.notify.ConfigField;
import com.fbads.rule.Rule;
import com.fbads.schedule.Schedule;
import com.fbads.service.AuthService;
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

    /**
     * Thân của mọi câu trả lời lỗi: { error } và, khi có, errors (lỗi từng ô nhập), drift (camp đã đổi kể từ lúc đó),
     * rateLimited (Facebook đang giới hạn số lần gọi). Trường không có thì không ghi.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String error, Map<String, String> errors, Boolean drift, Boolean rateLimited) {
        public static ApiError of(String error) { return new ApiError(error, null, null, null); }

        public static ApiError withErrors(String error, Map<String, String> errors) { return new ApiError(error, errors, null, null); }
    }

    /** { ok: true } */
    public record Ok(boolean ok) {
        public static final Ok OK = new Ok(true);
    }

    // ------------------------------------------------------------------ Cài đặt (SettingsController)

    /** Nơi lưu dữ liệu (Cài đặt → Chung). Bản Java luôn lưu MySQL nên các trường còn lại cố định. */
    public record Storage(String mode, String provider, Long lastSavedAt, String lastError, boolean pending) {
        public static final Storage MYSQL = new Storage("db", "MySQL", null, "", false);
    }

    /** GET /api/state: mọi thứ giao diện cần lúc mở */
    public record State(PublicSettings settings, List<Schedule> schedules, List<Rule> rules, Storage storage) {}

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

    // ------------------------------------------------------------------ Kênh thông báo (NotifyController)

    /** Một loại kênh có trong code: giao diện vẽ form từ fields; defaultTopics = loại tin chọn sẵn khi thêm kênh */
    public record NotifyType(String type, String label, List<ConfigField> fields, String help, List<String> defaultTopics) {}

    /** Một loại tin (Notice.Topic) */
    public record NotifyTopic(String key, String label) {}

    /**
     * Một kênh đã cài. config đã bỏ các ô bí mật (để ""), savedSecrets = các ô bí mật đã có giá trị (hiện "đã lưu").
     */
    public record NotifyTargetView(String id, String type, String label, String name, boolean enabled, List<String> topics,
            ObjectNode config, List<String> savedSecrets) {}

    /** GET /api/notify: các loại kênh, các loại tin, và kênh đã cài của workspace */
    public record NotifyOverview(List<NotifyType> types, List<NotifyTopic> topics, List<NotifyTargetView> channels) {}

    // ------------------------------------------------------------------ Đăng nhập (AuthController)

    /**
     * GET /api/auth: required = cần đăng nhập, authed = đã đăng nhập, setup = chưa có tài khoản nào, signup = được tự đăng
     * ký, envManaged = tài khoản quản trị đặt bằng biến môi trường, workspace đang chọn và mọi workspace của người này
     */
    public record AuthStatus(boolean required, boolean authed, boolean setup, boolean signup, boolean envManaged, UserInfo user,
            AuthService.Membership workspace, List<AuthService.Membership> workspaces) {}

    public record UserInfo(long id, String username, String name) {
        public static UserInfo of(User u) { return new UserInfo(u.getId(), u.getUsername(), u.getName()); }
    }

    // ------------------------------------------------------------------ Cài đặt, sức khoẻ

    /**
     * Cài đặt gửi về giao diện: mọi trường của AppSettings, riêng bí mật (token, App Secret) luôn để trống và thay bằng
     * cờ has_… (đã nhập chưa); has_notify = có kênh thông báo nào đang bật.
     */
    public record PublicSettings(@JsonUnwrapped AppSettings settings, String accessToken, String fbAppSecret,
            @JsonProperty("has_accessToken") boolean hasAccessToken, @JsonProperty("has_fbAppSecret") boolean hasFbAppSecret,
            @JsonProperty("has_notify") boolean hasNotify) {
        public static PublicSettings of(AppSettings s, boolean hasNotify) {
            return new PublicSettings(s, "", "", !s.getAccessToken().isEmpty(), !nullToEmpty(s.getFbAppSecret()).isEmpty(),
                    hasNotify);
        }

        private static String nullToEmpty(String v) { return v == null ? "" : v; }
    }

    /** GET /api/health: ok = vòng tự động còn chạy; lastTickAt = lúc xong lượt gần nhất (ISO) */
    public record Health(boolean ok, String lastTickAt) {}

    // ------------------------------------------------------------------ Kết nối Facebook (FacebookController)

    /** Token còn dùng được không, hết hạn lúc nào (ms) và còn mấy ngày, quyền đã cấp và quyền còn thiếu */
    public record TokenStatus(boolean valid, Long expiresAt, Long daysLeft, List<String> scopes, List<String> missing,
            String type, String appId) {}

    /** POST /api/fb/accounts: tên người dùng, tình trạng token, các tài khoản quảng cáo của token */
    public record FbAccounts(String user, TokenStatus token, List<FbAccount> accounts) {}

    /** Một tài khoản quảng cáo; tài khoản lỗi thì có error (và không có currency) */
    public record FbAccount(String id, String name, @JsonInclude(JsonInclude.Include.NON_NULL) String currency, String status,
            boolean active, @JsonInclude(JsonInclude.Include.NON_NULL) String error) {}

    /**
     * Trang Kết nối: ok, người dùng, từng tài khoản, tên/tiền tệ/trạng thái gộp, token. Chế độ dùng thử chỉ có
     * ok/mock/name/currency; lỗi chỉ có ok = false và error.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Connection(boolean ok, Boolean mock, String user, List<FbAccount> accounts, String name, String currency,
            String status, Boolean accountActive, TokenStatus token, Long checkedAt, String error) {
        public static Connection demo() {
            return new Connection(true, true, null, null, "Chế độ dùng thử (dữ liệu giả)", "VND", null, null, null, null, null);
        }

        public static Connection failed(String error) {
            return new Connection(false, null, null, null, null, null, null, null, null, null, error);
        }
    }

    /** GET /api/fb/oauth: địa chỉ cần khai báo trong ứng dụng Meta, lỗi của lần đăng nhập Facebook gần nhất ("" = không có) */
    public record OauthInfo(String redirectUri, String error) {}

    /** POST /api/fb/extend: token mới đã lưu và tình trạng của nó */
    public record TokenExtended(boolean ok, TokenStatus token) {}
}
