package com.fbads.notify;

import com.fbads.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Gửi tin Telegram cho mọi người nhận đã cài (bản Java của lib/notify.js). Một người lỗi không làm mất tin của người khác. */
@Service
public class TelegramService {
    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final Pattern SPLIT = Pattern.compile("[\\s,;]+");

    public record Result(String id, boolean ok, String error) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("ok", ok);
            if (error != null) m.put("error", error);
            return m;
        }
    }

    public record SendResult(boolean configured, List<Result> results) {
        public boolean anyOk() { return results.stream().anyMatch(Result::ok); }
    }

    /** Phản hồi cho giao diện: mã HTTP + nội dung */
    public record Reply(int status, Map<String, Object> body) {}

    private final SettingsService settings;
    private final RestClient http;
    private final JsonMapper mapper;
    private volatile String apiBase = "https://api.telegram.org";

    public TelegramService(SettingsService settings, RestClient.Builder builder, JsonMapper mapper) {
        this.settings = settings;
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        f.setReadTimeout(Duration.ofSeconds(20));
        this.http = builder.requestFactory(f).build();
        this.mapper = mapper;
    }

    /** Cho kiểm thử: trỏ tới máy chủ giả */
    public void setApiBase(String base) { this.apiBase = base; }

    /** Danh sách Chat ID: cách nhau bằng dấu phẩy / chấm phẩy / khoảng trắng, bỏ trùng (không phân biệt hoa thường) */
    public static List<String> chatIds(String v) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String raw : SPLIT.split(v == null ? "" : v)) {
            String id = raw.trim();
            if (!id.isEmpty()) seen.putIfAbsent(id.toLowerCase(), id);
        }
        return new ArrayList<>(seen.values());
    }

    /** Telegram trả mã lỗi + mô tả tiếng Anh → đổi thành việc cần làm */
    public static String friendly(int code, String desc) {
        String d = desc == null ? "" : desc.toLowerCase();
        if (code == 401) return "Bot Token sai hoặc đã bị thu hồi.";
        if (code == 400 && d.contains("chat not found")) return "Không tìm thấy chat này: ID sai, hoặc người đó chưa từng nhắn cho bot.";
        if (code == 400 && d.contains("too long")) return "Tin nhắn quá dài.";
        if (code == 403 && d.contains("blocked")) return "Người này đã chặn bot.";
        if (code == 403 && d.contains("initiate conversation")) return "Người này chưa từng nhắn cho bot nên bot chưa gửi tin được.";
        if (code == 403 && Pattern.compile("not a member|kicked|write|rights|deactivated").matcher(d).find()) return "Bot chưa ở trong nhóm/kênh này hoặc không có quyền gửi tin.";
        if (code == 429) return "Telegram đang giới hạn tốc độ gửi, thử lại sau ít phút.";
        return desc != null && !desc.isEmpty() ? "Telegram báo: " + desc : ("Lỗi Telegram " + (code == 0 ? "" : code)).trim();
    }

    private Result sendOne(String token, String id, String text) {
        try {
            return http.post().uri(apiBase + "/bot" + token + "/sendMessage").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", id, "text", text, "parse_mode", "HTML"))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) return new Result(id, true, null);
                        JsonNode j;
                        try { j = mapper.readTree(res.getBody()); } catch (RuntimeException e) { j = mapper.createObjectNode(); }
                        int code = j.path("error_code").asInt(res.getStatusCode().value());
                        // che token phòng khi mô tả lỗi nhắc lại nó: kết quả này được đưa ra giao diện và nhật ký
                        String msg = friendly(code, j.path("description").asString(null)).replace(token, "***");
                        return new Result(id, false, msg);
                    });
        } catch (RuntimeException e) {
            return new Result(id, false, "Không kết nối được tới Telegram. Kiểm tra mạng internet.");
        }
    }

    /** Gửi cho tất cả người nhận (song song trên virtual thread) */
    public SendResult send(String text) {
        String token = settings.get().getTelegramToken();
        List<String> ids = chatIds(settings.get().getTelegramChatId());
        if (token == null || token.isEmpty() || ids.isEmpty()) return new SendResult(false, List.of());
        List<Result> results = ids.parallelStream().map(id -> sendOne(token, id, text)).toList();
        for (Result r : results) if (!r.ok()) log.warn("Telegram lỗi ({}): {}", r.id(), r.error());
        return new SendResult(true, results);
    }

    public boolean telegram(String text) { return send(text).anyOk(); }

    /** Kết quả gửi → phản hồi: gửi được cho ít nhất một người thì 200, không ai nhận được thì 400 */
    public static Reply reply(SendResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!r.configured()) { body.put("error", "Cần nhập Bot Token và Chat ID trước."); return new Reply(400, body); }
        List<Map<String, Object>> rs = r.results().stream().map(Result::toJson).toList();
        long sent = r.results().stream().filter(Result::ok).count();
        if (sent > 0) {
            body.put("ok", true);
            body.put("sent", sent);
            body.put("total", r.results().size());
            body.put("results", rs);
            return new Reply(200, body);
        }
        String why = r.results().getFirst().error();
        body.put("error", r.results().size() > 1 ? "Không gửi được cho ai cả. Lỗi đầu tiên: " + why : "Gửi thất bại: " + why);
        body.put("results", rs);
        return new Reply(400, body);
    }
}
