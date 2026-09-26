package com.fbads.facebook;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Gửi 1 yêu cầu HTTP tới Graph API bằng RestClient, đọc mức dùng (RateLimits) và đổi lỗi Facebook thành FbException tiếng Việt.
 * @Retry (Resilience4j): lỗi MẠNG thì tự thử lại tối đa 3 lần (cấu hình resilience4j.retry.instances.graph);
 * lỗi do Facebook trả về thì không thử lại (thử lại chỉ tốn thêm lượt gọi).
 */
@Component
public class GraphClient {
    public static final String BASE = "https://graph.facebook.com";

    private final RestClient http;
    private final RateLimits limits;
    private final JsonMapper mapper;

    public GraphClient(RestClient.Builder builder, RateLimits limits, JsonMapper mapper) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = builder.requestFactory(factory).build();
        this.limits = limits;
        this.mapper = mapper;
    }

    /** Chuỗi query/body dạng form: a=1&b=2 (mã hoá URL) */
    public static String form(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        params.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    /**
     * GET (params nằm trên URL) hoặc POST (params trong body). request = mô tả yêu cầu đã bỏ bí mật, để ghi vào lỗi.
     * Ném ResourceAccessException khi lỗi mạng (để @Retry thử lại), FbException khi Facebook báo lỗi.
     */
    @Retry(name = "graph")
    public JsonNode send(HttpMethod method, URI uri, String body, Map<String, Object> request) {
        RestClient.RequestBodySpec spec = http.method(method).uri(uri);
        if (body != null) spec.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body);
        return spec.exchange((req, res) -> {
            limits.readUsage(res.getHeaders());
            int status = res.getStatusCode().value();
            JsonNode json;
            try {
                json = mapper.readTree(res.getBody());
            } catch (JacksonException e) {
                throw new FbException("Facebook trả về dữ liệu không đọc được (HTTP " + status + ").", info(request, "httpStatus", status));
            }
            JsonNode er = json.get("error");
            if (er != null && !er.isNull()) {
                int code = er.path("code").asInt(0);
                // bị giới hạn mà Facebook không báo thời gian chờ → nghỉ 5 phút
                if (FbException.isRateLimitCode(code)) limits.block(5 * 60_000);
                Map<String, Object> fb = new LinkedHashMap<>();
                fb.put("code", code);
                if (er.has("error_subcode")) fb.put("subcode", er.path("error_subcode").asInt());
                putText(fb, "type", er.get("type"));
                putText(fb, "fbtraceId", er.get("fbtrace_id"));
                putText(fb, "userMsg", er.get("error_user_msg"));
                putText(fb, "rawMessage", er.get("message"));
                fb.put("httpStatus", status);
                fb.put("request", request);
                throw new FbException(friendly(er, limits), fb);
            }
            return json;
        });
    }

    private static void putText(Map<String, Object> m, String k, JsonNode v) {
        if (v != null && !v.isNull()) m.put(k, v.asString());
    }

    private static Map<String, Object> info(Map<String, Object> request, String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        m.put("request", request);
        return m;
    }

    /** Dịch lỗi Facebook sang tiếng Việt dễ hiểu, kèm việc cần làm. */
    public static String friendly(JsonNode err, RateLimits limits) {
        int c = err.path("code").asInt(0), sub = err.path("error_subcode").asInt(0);
        String raw = err.hasNonNull("error_user_msg") ? err.get("error_user_msg").asString()
                : err.hasNonNull("message") ? err.get("message").asString() : "Lỗi không xác định";
        return friendly(c, sub, raw, limits);
    }

    public static String friendly(int c, int sub, String raw, RateLimits limits) {
        if (c == 190) return "Token đã hết hạn hoặc không hợp lệ. Hãy tạo token mới và dán lại trong Cài đặt.";
        if (c == 10 || c == 200 || c == 294 || c == 278)
            return "Token chưa đủ quyền. Cần tick quyền ads_management và ads_read, và tài khoản quảng cáo phải được gán cho token/người dùng này.";
        if (FbException.isRateLimitCode(c)) {
            long min = Math.max(1, (long) Math.ceil((limits.blockedUntil() - System.currentTimeMillis()) / 60000.0));
            return "Facebook đang giới hạn số lần gọi. Tool tạm ngưng gọi và tự thử lại sau khoảng " + min + " phút.";
        }
        if (c == 100 && raw != null && raw.matches("(?is).*(act_|ad account|nonexisting|does not exist).*"))
            return "Ad Account ID không đúng, hoặc token không có quyền vào tài khoản này. Hãy chọn lại tài khoản từ danh sách.";
        if (c == 100 && sub == 1487225) return "Không thể đổi ngân sách ở cấp này (camp đang dùng ngân sách chiến dịch - CBO).";
        return raw;
    }
}
