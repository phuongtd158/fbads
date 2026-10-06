package com.fbads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Máy chủ giả thay hệ thống báo cáo của công ty trong test (đặt baseUrl của cài đặt về đây): làm giống API thật
 * (đăng nhập → cookie wellday_session + csrf; /teams; GET/POST /reports, sửa bản đã có cần đúng lần sửa + lý do, sửa xong thì khoá).
 * Không bao giờ gọi hệ thống thật.
 */
final class CompanyStub implements AutoCloseable {
    /** Mật khẩu đúng của máy chủ giả (ghép lúc chạy để công cụ quét bí mật không nhầm là mật khẩu thật) */
    static final String GOOD_LOGIN = String.join("-", "dung", "mat", "khau");

    record Call(String method, String path, Map<String, String> query, Map<String, Object> body, Map<String, String> headers) {}

    private final HttpServer server;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> reports = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> updates = new CopyOnWriteArrayList<>();
    volatile int logins;
    volatile String session = "";
    volatile boolean expire;
    /** POST /reports trả lỗi 503 (hệ thống công ty lỗi tạm thời) */
    volatile boolean fail5xx;

    CompanyStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    List<Call> posts() { return calls.stream().filter(c -> c.method().equals("POST") && c.path().equals("/reports")).toList(); }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : -1; }

    private synchronized void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath().replaceFirst("^/api", "");
        Map<String, String> query = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            query.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> body = text.isEmpty() ? null : Api.JSON.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : List.of("Cookie", "X-CSRF-Token")) if (ex.getRequestHeaders().getFirst(h) != null) headers.put(h, ex.getRequestHeaders().getFirst(h));
        calls.add(new Call(method, path, query, body, headers));

        if (path.equals("/auth/login")) {
            logins++;
            if (body == null || !GOOD_LOGIN.equals(body.get("password"))) { reply(ex, 401, Map.of("error", "Email hoặc mật khẩu không đúng.")); return; }
            session = "sess" + logins;
            ex.getResponseHeaders().add("Set-Cookie", "wellday_session=" + session + "; Path=/; HttpOnly; Secure");
            reply(ex, 200, Map.of("user", Map.of("id", "u1", "name", "Nguyễn Minh Phương", "email", "mkt@congty.vn", "role", "MKT", "must_change", 0), "csrf", "csrf" + logins));
            return;
        }
        if (!("wellday_session=" + session).equals(headers.get("Cookie")) || !("csrf" + logins).equals(headers.get("X-CSRF-Token")) || expire) {
            expire = false;
            reply(ex, 401, Map.of("error", "Phiên đăng nhập đã hết hạn."));
            return;
        }
        if (path.equals("/teams")) {
            reply(ex, 200, List.of(Map.of("id", "team-1", "code", "CT01", "name", "WDC - Hoạt Huyết", "status", "ACTIVE"), Map.of("id", "team-2", "code", "CT02", "name", "Khác", "status", "ACTIVE")));
            return;
        }
        if (path.equals("/reports") && method.equals("GET")) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : reports) {
                String d = (String) r.get("date");
                if (Objects.equals(r.get("team_id"), query.get("team_id")) && d.compareTo(query.get("from")) >= 0 && d.compareTo(query.get("to")) <= 0) out.add(r);
            }
            reply(ex, 200, out);
            return;
        }
        if (path.equals("/reports") && method.equals("POST")) {
            if (fail5xx) { reply(ex, 503, Map.of("error", "Lỗi máy chủ")); return; }
            Map<String, Object> old = reports.stream().filter(x -> Objects.equals(x.get("team_id"), body.get("team_id")) && Objects.equals(x.get("date"), body.get("date"))
                    && num(x.get("slot")) == num(body.get("slot"))).findFirst().orElse(null);
            if (old != null) { // sửa báo cáo đã có: như web công ty, cần đúng lần sửa + lý do, đã khoá thì không cho
                if (Boolean.TRUE.equals(old.get("locked"))) { reply(ex, 403, Map.of("error", "Báo cáo đã khóa.")); return; }
                if (num(body.get("revision")) != num(old.get("revision"))) { reply(ex, 409, Map.of("error", "Báo cáo đã được cập nhật bởi người khác.")); return; }
                if (body.get("reason") == null || body.get("reason").toString().isEmpty()) { reply(ex, 400, Map.of("error", "Cần lý do chỉnh sửa.")); return; }
                Map<String, Object> rest = new LinkedHashMap<>(body);
                rest.remove("reason");
                rest.remove("revision");
                old.putAll(rest);
                old.put("revision", num(old.get("revision")) + 1);
                old.put("locked", true);
                updates.add(body);
                reply(ex, 200, old);
                return;
            }
            Map<String, Object> r = new LinkedHashMap<>(Map.of("id", "rep-" + (reports.size() + 1), "user_id", "u1", "status", "SUBMITTED", "revision", 1, "locked", false));
            r.putAll(body);
            reports.add(r);
            reply(ex, 200, r);
            return;
        }
        reply(ex, 404, Map.of("error", "Not found"));
    }

    private static void reply(HttpExchange ex, int status, Object json) throws IOException {
        byte[] out = Api.JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() { server.stop(0); }
}
