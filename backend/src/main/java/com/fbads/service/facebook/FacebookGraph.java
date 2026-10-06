package com.fbads.service.facebook;

import com.fbads.client.FbException;
import com.fbads.client.GraphClient;
import com.fbads.client.RateLimits;
import com.fbads.entity.AppSettings;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookState.TokenError;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gọi Graph API của Facebook: gắn token, lấy hết các trang, đổi lỗi mạng / hết giờ thành câu dễ hiểu.
 * Các class Facebook* khác đều gọi Facebook qua đây.
 */
@Component
public class FacebookGraph {
    private static final Set<String> SECRET_KEYS = Set.of("access_token", "client_secret", "fb_exchange_token",
            "input_token", "code");

    private final SettingsService settings;
    private final GraphClient graph;
    private final RateLimits limits;
    private final FacebookState state;
    private volatile String graphBase = GraphClient.BASE;

    public FacebookGraph(SettingsService settings, GraphClient graph, RateLimits limits, FacebookState state) {
        this.settings = settings;
        this.graph = graph;
        this.limits = limits;
        this.state = state;
    }

    static String actOf(String id) {
        return "act_" + id;
    }

    /** Tham số ghi vào nhật ký: bỏ token / mật khẩu, cắt chuỗi quá dài */
    private static Map<String, Object> cleanParams(Map<String, String> p) {
        Map<String, Object> o = new LinkedHashMap<>();
        p.forEach((k, v) -> {
            if (!SECRET_KEYS.contains(k)) o.put(k, v.length() > 300 ? v.substring(0, 300) : v);
        });
        return o;
    }

    /** tokenOverride = null: dùng token trong Cài đặt */
    JsonNode call(String method, String path, Map<String, String> params, String tokenOverride) {
        AppSettings s = settings.get();
        String token = tokenOverride != null ? tokenOverride : s.getAccessToken();
        if (token == null || token.isEmpty()) throw new FbException("Chưa nhập Access Token trong Cài đặt", null);
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("access_token", token);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("path", "/" + s.getApiVersion() + "/" + path);
        request.put("params", cleanParams(params));
        String base = graphBase + "/" + s.getApiVersion() + "/" + path;
        try {
            if (method.equals("GET")) {
                return send(HttpMethod.GET, URI.create(base + "?" + GraphClient.form(all)), null, request);
            }
            return send(HttpMethod.valueOf(method), URI.create(base), GraphClient.form(all), request);
        } catch (FbException e) {
            // Token trong Cài đặt bị Facebook báo hỏng (hết hạn, bị thu hồi): ghi lại để báo Telegram ngay
            // (engine/EngineWatch)
            if (tokenOverride == null && Integer.valueOf(190).equals(e.code())) {
                state.ws().tokenError = new TokenError(token, e.getMessage());
            }
            throw e;
        }
    }

    /** Lỗi token gần nhất của token đang dùng trong Cài đặt; "" = không có (hoặc đã đổi token khác) */
    public String tokenError() {
        TokenError t = state.ws().tokenError;
        return t != null && t.token().equals(settings.get().getAccessToken()) ? t.message() : "";
    }

    /** Cho kiểm thử: trỏ tới máy chủ giả thay graph.facebook.com */
    public void setGraphBase(String base) {
        this.graphBase = base;
    }

    private JsonNode send(HttpMethod m, URI uri, String body, Map<String, Object> request) {
        boolean read = m == HttpMethod.GET;
        try {
            return read ? graph.read(uri, request) : graph.write(m, uri, body, request);
        } catch (ResourceAccessException e) {
            Map<String, Object> fb = new LinkedHashMap<>();
            fb.put("network", true);
            fb.put("request", request);
            if (isTimeout(e)) {
                fb.put("timeout", true);
                long sec = Math.round(graph.timeout().toMillis() / 1000.0);
                throw new FbException(read ? "Facebook không trả lời sau " + sec + " giây."
                        : "Facebook không trả lời sau " + sec + " giây. Không rõ thao tác đã được thực hiện chưa, hãy "
                                + "bấm Làm mới để xem trạng thái thật trên Facebook.", fb);
            }
            fb.put("systemMessage", String.valueOf(e.getMostSpecificCause().getMessage()));
            throw new FbException("Không kết nối được tới Facebook. Kiểm tra mạng internet.", fb);
        }
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    /** Lấy hết các trang (500 mục/trang để ít lượt gọi) */
    List<JsonNode> callAll(String path, Map<String, String> params, String token) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("limit", "500");
        p.putAll(params);
        JsonNode json = call("GET", path, p, token);
        List<JsonNode> out = new ArrayList<>();
        json.path("data").forEach(out::add);
        while (json.path("paging").hasNonNull("next")) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("method", "GET");
            req.put("path", "/" + path);
            req.put("params", Map.of("page", "next"));
            json = send(HttpMethod.GET, URI.create(json.path("paging").path("next").asString()), null, req);
            json.path("data").forEach(out::add);
        }
        return out;
    }

    /** Lỗi "đang bị Facebook giới hạn số lần gọi" khi chưa có số cũ nào để trả */
    FbException rateLimitError() {
        return new FbException(GraphClient.friendly(17, 0, "", limits), new LinkedHashMap<>(Map.of("code", 17)));
    }
}
