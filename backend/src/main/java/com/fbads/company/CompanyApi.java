package com.fbads.company;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fbads.common.ApiException;
import com.fbads.common.Hash;
import com.fbads.company.CompanyJson.ErrorBody;
import com.fbads.company.CompanyJson.LoginResponse;
import com.fbads.company.CompanyJson.RemoteReport;
import com.fbads.company.CompanyJson.User;
import com.fbads.security.WorkspaceContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gọi API hệ thống báo cáo nội bộ của công ty (bản Java của lib/companyApi.js), giống hệt trang web của công ty:
 *  - Đăng nhập: POST /api/auth/login { email, password } → cookie `wellday_session` + mã `csrf` trong body.
 *  - Mọi request sau gửi kèm cookie và header X-CSRF-Token. Gặp 401 (phiên hết hạn) → đăng nhập lại 1 lần rồi thử lại.
 *  - Team: GET /api/teams. Báo cáo: GET /api/reports?from&to&team_id, gửi / cập nhật: POST /api/reports.
 * Phiên đăng nhập chỉ giữ trong bộ nhớ, mỗi workspace một phiên. Mật khẩu không bao giờ ra log/giao diện.
 * Lỗi mạng (502) và hết giờ (504) là lỗi tạm thời: chế độ Tự động gửi thử lại.
 */
@Component
public class CompanyApi {
    public static final Duration TIMEOUT = Duration.ofSeconds(20);
    static final String COOKIE = "wellday_session";
    private static final Pattern COOKIE_RE = Pattern.compile("(?:^|[;,]\\s*)" + COOKIE + "=([^;]+)");

    /** Phiên đăng nhập; key = dấu của (địa chỉ, email, mật khẩu): đổi tài khoản / địa chỉ thì đăng nhập lại */
    public record Session(String key, String cookie, String csrf, User user) {}

    /** Một Team trên hệ thống công ty (GET /api/teams) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Team(String id, String code, String name, String status) {
        public Team {
            id = id == null ? "" : id;
            code = code == null ? "" : code;
            name = name == null ? "" : name;
            status = status == null ? "" : status;
        }
    }

    /** Câu trả lời thô: mã HTTP, thân (null nếu không phải JSON), các dòng Set-Cookie */
    private record Raw(int status, String body, List<String> cookies) {}

    private final JsonMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private volatile Duration timeout = TIMEOUT;

    public CompanyApi(JsonMapper mapper) { this.mapper = mapper; }

    /** Đổi thời gian chờ (test) */
    public void setTimeout(Duration d) { this.timeout = d; }

    private static String keyOf(CompanyConfig c) { return Hash.sha256(c.getBaseUrl() + "|" + c.getEmail() + "|" + c.getPassword()); }

    private Raw raw(CompanyConfig c, String path, String method, Object body, Session auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.getBaseUrl() + "/api" + path)).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (auth != null) b.header("Cookie", COOKIE + "=" + auth.cookie()).header("X-CSRF-Token", auth.csrf());
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ApiException(504, "Hệ thống công ty không trả lời sau " + Math.round(timeout.toMillis() / 1000.0) + " giây.");
        } catch (IOException e) {
            throw new ApiException(502, "Không kết nối được tới hệ thống công ty. Kiểm tra mạng hoặc địa chỉ hệ thống.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "Không kết nối được tới hệ thống công ty. Kiểm tra mạng hoặc địa chỉ hệ thống.");
        }
        return new Raw(res.statusCode(), res.body(), res.headers().allValues("set-cookie"));
    }

    /** Đọc thân trả về thành kiểu T; rỗng hoặc không đọc được (không phải JSON, sai hình dạng) → null */
    private <T> T parse(Raw r, Class<T> type) {
        if (r.body() == null || r.body().isBlank()) return null;
        try {
            return mapper.readValue(r.body(), type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Như parse nhưng cho danh sách; không phải mảng → danh sách rỗng */
    private <T> List<T> parseList(Raw r, Class<T> type) {
        if (r.body() == null || r.body().isBlank()) return List.of();
        try {
            return mapper.readValue(r.body(), mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static String cookieFrom(List<String> lines) {
        for (String line : lines) { Matcher m = COOKIE_RE.matcher(line); if (m.find()) return m.group(1); }
        return "";
    }

    private String errOf(Raw r) {
        ErrorBody e = parse(r, ErrorBody.class);
        return e == null ? "" : e.error();
    }

    public Session login(CompanyConfig c) {
        if (c.getEmail().isEmpty() || c.getPassword().isEmpty())
            throw new ApiException(400, "Chưa nhập email/mật khẩu hệ thống công ty (Cài đặt → Báo cáo công ty).");
        Raw r = raw(c, "/auth/login", "POST", Map.of("email", c.getEmail(), "password", c.getPassword()), null);
        String cookie = cookieFrom(r.cookies());
        String err = errOf(r);
        if (r.status() == 401 || r.status() == 400)
            throw new ApiException(400, "Hệ thống công ty từ chối đăng nhập: " + (err.isEmpty() ? "sai email hoặc mật khẩu" : err) + ".");
        if (r.status() == 429)
            throw new ApiException(429, "Hệ thống công ty đang chặn đăng nhập vì thử quá nhiều lần. Đợi vài phút rồi thử lại.");
        if (r.status() < 200 || r.status() >= 300)
            throw new ApiException(502, "Đăng nhập hệ thống công ty lỗi " + r.status() + (err.isEmpty() ? "" : ": " + err) + ".");
        LoginResponse body = parse(r, LoginResponse.class);
        String csrf = body == null ? "" : body.csrf();
        if (cookie.isEmpty() || csrf.isEmpty())
            throw new ApiException(502, "Đăng nhập hệ thống công ty không trả về phiên làm việc (có thể API đã đổi).");
        User user = body.user();
        if (user != null && user.mustChangePassword())
            throw new ApiException(400, "Tài khoản công ty đang bắt đổi mật khẩu. Hãy đăng nhập trên web công ty, đổi "
                    + "mật khẩu rồi nhập mật khẩu mới vào tool.");
        Session s = new Session(keyOf(c), cookie, csrf, user);
        sessions.put(WorkspaceContext.require(), s);
        return s;
    }

    private Session ensure(CompanyConfig c) {
        Session s = sessions.get(WorkspaceContext.require());
        return s != null && s.key().equals(keyOf(c)) ? s : login(c);
    }

    /** Gọi API có đăng nhập; phiên hết hạn (401) → đăng nhập lại đúng 1 lần. Lỗi → ApiException */
    private Raw call(CompanyConfig c, String path, String method, Object body) {
        Session s = ensure(c);
        Raw r = raw(c, path, method, body, s);
        if (r.status() == 401) {
            sessions.remove(WorkspaceContext.require());
            s = login(c);
            r = raw(c, path, method, body, s);
        }
        if (r.status() < 200 || r.status() >= 300) {
            String err = errOf(r);
            throw new ApiException(r.status() >= 500 ? 502 : 400,
                    "Hệ thống công ty báo lỗi" + (err.isEmpty() ? " " + r.status() : ": " + err));
        }
        return r;
    }

    /** Kiểm tra kết nối: đăng nhập lại từ đầu → { name, email, role } */
    public Map<String, String> test(CompanyConfig c) {
        sessions.remove(WorkspaceContext.require());
        Session s = login(c);
        User u = s.user() == null ? new User(null, null, null, null, null) : s.user();
        return Map.of("name", u.name(), "email", u.email(), "role", u.role());
    }

    public List<Team> listTeams(CompanyConfig c) {
        return parseList(call(c, "/teams", "GET", null), Team.class);
    }

    /** Báo cáo của chính tài khoản này cho một Team trong khoảng ngày (date cắt còn YYYY-MM-DD) */
    public List<RemoteReport> listReports(CompanyConfig c, String teamId, String from, String to) {
        Session s = ensure(c);
        String q = "from=" + enc(from) + "&to=" + enc(to) + "&team_id=" + enc(teamId);
        List<RemoteReport> list = parseList(call(c, "/reports?" + q, "GET", null), RemoteReport.class);
        Session now = sessions.getOrDefault(WorkspaceContext.require(), s);
        String me = now.user() == null ? null : now.user().id();
        List<RemoteReport> out = new ArrayList<>();
        for (RemoteReport r : list) {
            if (!teamId.equals(r.teamId())) continue;
            boolean someoneElse = me != null && !me.isEmpty() && r.userId() != null && !me.equals(r.userId());
            if (someoneElse) continue;
            out.add(r.withDayOnly());
        }
        return out;
    }

    /** Báo cáo của chính tài khoản này cho Team/ngày/mốc đã có chưa → báo cáo đó hoặc null */
    public RemoteReport findReport(CompanyConfig c, String teamId, String date, int slot) {
        for (RemoteReport r : listReports(c, teamId, date, date)) if (r.isFor(date, slot)) return r;
        return null;
    }

    /** Gửi (mới hoặc cập nhật) một báo cáo; trả phần công ty trả về (null nếu trống / không đọc được) */
    public RemoteReport submitReport(CompanyConfig c, Object payload) {
        return parse(call(c, "/reports", "POST", payload), RemoteReport.class);
    }

    /** Quên phiên của workspace hiện tại (đổi cài đặt → đăng nhập lại ở lần gọi sau) */
    public void reset() { sessions.remove(WorkspaceContext.require()); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
