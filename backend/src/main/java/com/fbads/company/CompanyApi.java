package com.fbads.company;

import com.fbads.common.ApiException;
import com.fbads.common.Hash;
import com.fbads.entity.CompanyConfig;
import com.fbads.security.WorkspaceContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
    public record Session(String key, String cookie, String csrf, JsonNode user) {}

    public record Team(String id, String code, String name, String status) {}

    private record Raw(int status, JsonNode json, List<String> cookies) {}

    private final JsonMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
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
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
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
        JsonNode json = null;
        try { json = mapper.readTree(res.body()); } catch (RuntimeException ignored) { /* không phải JSON */ }
        if (json != null && json.isMissingNode()) json = null;
        return new Raw(res.statusCode(), json, res.headers().allValues("set-cookie"));
    }

    static String cookieFrom(List<String> lines) {
        for (String line : lines) { Matcher m = COOKIE_RE.matcher(line); if (m.find()) return m.group(1); }
        return "";
    }

    private static String errOf(JsonNode json) { return json == null ? "" : json.path("error").asString(""); }

    public Session login(CompanyConfig c) {
        if (c.getEmail().isEmpty() || c.getPassword().isEmpty()) throw new ApiException(400, "Chưa nhập email/mật khẩu hệ thống công ty (Cài đặt → Báo cáo công ty).");
        Raw r = raw(c, "/auth/login", "POST", Map.of("email", c.getEmail(), "password", c.getPassword()), null);
        String cookie = cookieFrom(r.cookies());
        String err = errOf(r.json());
        if (r.status() == 401 || r.status() == 400) throw new ApiException(400, "Hệ thống công ty từ chối đăng nhập: " + (err.isEmpty() ? "sai email hoặc mật khẩu" : err) + ".");
        if (r.status() == 429) throw new ApiException(429, "Hệ thống công ty đang chặn đăng nhập vì thử quá nhiều lần. Đợi vài phút rồi thử lại.");
        if (r.status() < 200 || r.status() >= 300) throw new ApiException(502, "Đăng nhập hệ thống công ty lỗi " + r.status() + (err.isEmpty() ? "" : ": " + err) + ".");
        String csrf = r.json() == null ? "" : r.json().path("csrf").asString("");
        if (cookie.isEmpty() || csrf.isEmpty()) throw new ApiException(502, "Đăng nhập hệ thống công ty không trả về phiên làm việc (có thể API đã đổi).");
        JsonNode user = r.json().path("user");
        if (user.path("must_change").asBoolean(false))
            throw new ApiException(400, "Tài khoản công ty đang bắt đổi mật khẩu. Hãy đăng nhập trên web công ty, đổi mật khẩu rồi nhập mật khẩu mới vào tool.");
        Session s = new Session(keyOf(c), cookie, csrf, user.isObject() ? user : null);
        sessions.put(WorkspaceContext.require(), s);
        return s;
    }

    private Session ensure(CompanyConfig c) {
        Session s = sessions.get(WorkspaceContext.require());
        return s != null && s.key().equals(keyOf(c)) ? s : login(c);
    }

    /** Gọi API có đăng nhập; phiên hết hạn (401) → đăng nhập lại đúng 1 lần */
    private JsonNode call(CompanyConfig c, String path, String method, Object body) {
        Session s = ensure(c);
        Raw r = raw(c, path, method, body, s);
        if (r.status() == 401) {
            sessions.remove(WorkspaceContext.require());
            s = login(c);
            r = raw(c, path, method, body, s);
        }
        if (r.status() < 200 || r.status() >= 300) {
            String err = errOf(r.json());
            throw new ApiException(r.status() >= 500 ? 502 : 400, "Hệ thống công ty báo lỗi" + (err.isEmpty() ? " " + r.status() : ": " + err));
        }
        return r.json();
    }

    /** Kiểm tra kết nối: đăng nhập lại từ đầu → { name, email, role } */
    public Map<String, String> test(CompanyConfig c) {
        sessions.remove(WorkspaceContext.require());
        Session s = login(c);
        JsonNode u = s.user() == null ? mapper.createObjectNode() : s.user();
        return Map.of("name", u.path("name").asString(""), "email", u.path("email").asString(""), "role", u.path("role").asString(""));
    }

    public List<Team> listTeams(CompanyConfig c) {
        JsonNode list = call(c, "/teams", "GET", null);
        List<Team> out = new ArrayList<>();
        if (list != null && list.isArray()) for (JsonNode t : list)
            out.add(new Team(t.path("id").asString(""), t.path("code").asString(""), t.path("name").asString(""), t.path("status").asString("")));
        return out;
    }

    /** Báo cáo của chính tài khoản này cho một Team trong khoảng ngày (date cắt còn YYYY-MM-DD) */
    public List<JsonNode> listReports(CompanyConfig c, String teamId, String from, String to) {
        Session s = ensure(c);
        String q = "from=" + enc(from) + "&to=" + enc(to) + "&team_id=" + enc(teamId);
        JsonNode list = call(c, "/reports?" + q, "GET", null);
        Session now = sessions.getOrDefault(WorkspaceContext.require(), s);
        String me = now.user() == null || now.user().path("id").isMissingNode() || now.user().path("id").isNull() ? null : now.user().path("id").asString("");
        List<JsonNode> out = new ArrayList<>();
        if (list != null && list.isArray()) for (JsonNode r : list) {
            if (!teamId.equals(r.path("team_id").asString(""))) continue;
            JsonNode uid = r.path("user_id");
            if (me != null && !me.isEmpty() && !uid.isMissingNode() && !uid.isNull() && !me.equals(uid.asString(""))) continue;
            tools.jackson.databind.node.ObjectNode x = ((tools.jackson.databind.node.ObjectNode) r).deepCopy();
            String d = r.path("date").asString("");
            x.put("date", d.length() > 10 ? d.substring(0, 10) : d);
            out.add(x);
        }
        return out;
    }

    /** Báo cáo của chính tài khoản này cho Team/ngày/mốc đã có chưa → báo cáo đó hoặc null */
    public JsonNode findReport(CompanyConfig c, String teamId, String date, int slot) {
        for (JsonNode r : listReports(c, teamId, date, date)) if (date.equals(r.path("date").asString("")) && r.path("slot").asDouble(-1) == slot) return r;
        return null;
    }

    public JsonNode submitReport(CompanyConfig c, Map<String, Object> payload) { return call(c, "/reports", "POST", payload); }

    /** Quên phiên của workspace hiện tại (đổi cài đặt → đăng nhập lại ở lần gọi sau) */
    public void reset() { sessions.remove(WorkspaceContext.require()); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
