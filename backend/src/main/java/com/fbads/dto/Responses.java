package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fbads.company.CompanyApi;
import com.fbads.engine.DecisionStatus;
import com.fbads.entity.AppSettings;
import com.fbads.entity.CompanyReport;
import com.fbads.entity.LogEntry;
import com.fbads.entity.Rule;
import com.fbads.entity.Schedule;
import com.fbads.entity.User;
import com.fbads.notify.ConfigField;
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

    // ------------------------------------------------------------------ Camp / nhóm QC (ObjectsController)

    /** Mức dùng API Facebook: phần trăm và loại giới hạn */
    public record Usage(double pct, String tier) {}

    /**
     * Thông tin kèm danh sách camp: số liệu lúc nào (at), có phải số cũ không (stale), bị chặn tới khi nào
     * (blockedUntil), mức dùng API, tài khoản và lỗi từng tài khoản
     */
    public record ObjectsMeta(Long at, boolean stale, Long blockedUntil, Usage usage,
            List<AccountRef> accounts, List<AccountError> accountErrors) {}

    /** Một tài khoản quảng cáo đang quản lý */
    public record AccountRef(String id, String name, String currency) {}

    /** Tài khoản tải lỗi (các tài khoản khác vẫn hiện) */
    public record AccountError(String id, String name, String error) {}

    /** GET /api/objects: camp + nhóm QC kèm số hôm nay, và các trường của ObjectsMeta */
    public record ObjectsList(List<AdObject> items, Long at, boolean stale, Long blockedUntil, Usage usage,
            List<AccountRef> accounts, List<AccountError> accountErrors) {
        public ObjectsList(List<AdObject> items, ObjectsMeta m) {
            this(items, m.at(), m.stale(), m.blockedUntil(), m.usage(), m.accounts(), m.accountErrors());
        }
    }

    /** GET /api/insights: số liệu theo khoảng ngày, metrics = { [id camp/nhóm QC]: số liệu } */
    public record Insights(Map<String, Object> range, String key, String since, String until, Integer days, Long at,
            boolean stale, Long blockedUntil, Usage usage, List<AccountError> accountErrors,
            Map<String, Metrics> metrics) {}

    /** Một lần bật / tắt / đổi ngân sách (lấy từ Nhật ký) để đánh dấu trên biểu đồ xu hướng */
    public record TrendEvent(String ts, String date, String type, String source, String detail) {}

    /** GET /api/objects/{id}/trend: days = [{ date, spend, … }] */
    public record Trend(String id, String since, String until, List<TrendDay> days,
            List<TrendEvent> events, Long at, boolean stale, Long blockedUntil) {}

    /** Số liệu của một ngày trên biểu đồ xu hướng: { date, spend, impressions, … } (các trường của Metrics nằm cùng cấp) */
    public record TrendDay(String date, @JsonUnwrapped Metrics metrics) {}

    /** POST /api/logs/{id}/undo */
    public record Undone(boolean ok, LogEntry entry) {}

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

    // ------------------------------------------------------------------ Rule (AutomationController)

    /** Xem trước rule: khoảng tính, chế độ, có thay đổi thật không, từng camp và kết quả xét */
    public record RulePreview(String range, String mode, boolean willChange, List<PreviewItem> items, Counts counts,
            List<String> warnings) {
        public RulePreview withWarnings(List<String> w) { return new RulePreview(range, mode, willChange, items, counts, w); }
    }

    /** Một camp trong xem trước: trạng thái xét (status/code/reason), giá trị so sánh, từng điều kiện, việc sẽ làm */
    public record PreviewItem(String id, String name, AdLevel level, String effective, boolean learning, Double budget,
            DecisionStatus status, String code, String reason, boolean hit, Double value, boolean inf, double spend,
            double results, List<PreviewCond> conds, PreviewResult result) {}

    /** Một điều kiện trong xem trước. Các trường cuối chỉ có khi dùng: khoảng so sánh, bậc theo kết quả, rule tăng theo bậc */
    public record PreviewCond(String metric, String op, String vs, Double factor, Double threshold, Double actual, boolean inf,
            boolean hit, boolean unknown,
            @JsonInclude(JsonInclude.Include.NON_NULL) String compareRange,
            @JsonInclude(JsonInclude.Include.NON_NULL) String tierMetric,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double tierCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double tierAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer ladderStep,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer ladderNeed) {}

    /** Việc rule sẽ làm với camp này; notify = chỉ báo, không đổi gì */
    public record PreviewResult(String detail, @JsonProperty("notify") boolean notifyOnly) {}

    public record Counts(long match, int total) {}

    /** Hoạt động 7 ngày của một rule: số lần tác động, số lần lỗi, lần gần nhất, số mục đang chờ bật lại */
    public record RuleActivity(int acts, int errors, LastRun last, int resumePending) {}

    /** Lần chạy gần nhất của một rule (theo nhật ký) */
    public record LastRun(String ts, String name, String detail, boolean ok, boolean dry) {}

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
