package com.fbads.notify.channel;

import com.fbads.notify.ConfigField;
import com.fbads.notify.Notice;
import com.fbads.notify.NotifyChannel;
import com.fbads.notify.SendResult.Recipient;
import com.fbads.notify.SendResult;
import com.fbads.validation.Checks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kênh Telegram: gửi qua Bot API (sendMessage, parse_mode HTML) cho mọi Chat ID đã cài.
 * Một người lỗi không làm mất tin của người khác.
 * <p>
 * Cấu hình: {@code {"token": "123:AA…", "chatId": "111, -100222, @kenh"}}.
 */
@Component
public class TelegramChannel implements NotifyChannel {
    public static final String TYPE = "telegram";
    /** Telegram treo thì báo lỗi sau chừng này, không giữ chân vòng tự động */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final Pattern SPLIT = Pattern.compile("[\\s,;]+");

    private final RestClient http;
    private final JdkClientHttpRequestFactory factory;
    private final JsonMapper mapper;
    private volatile Duration timeout = TIMEOUT;
    private volatile String apiBase = "https://api.telegram.org";

    public TelegramChannel(RestClient.Builder builder, JsonMapper mapper) {
        this.factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(TIMEOUT);
        this.http = builder.requestFactory(factory).build();
        this.mapper = mapper;
    }

    @Override
    public String type() { return TYPE; }

    @Override
    public String label() { return "Telegram"; }

    @Override
    public List<ConfigField> fields() {
        return List.of(
                new ConfigField("token", "Bot Token", true, "123456789:AAxxxxxxxx…", ""),
                new ConfigField("chatId", "Chat ID người nhận", false, "Vd: 123456789, -100987654321, @kenhcuaban",
                        "Nhiều người thì cách nhau bằng dấu phẩy (tối đa " + Checks.MAX_TG_CHATS + "). Mỗi người phải nhắn cho bot "
                                + "ít nhất một tin trước, hoặc dùng Chat ID của một nhóm có bot."));
    }

    @Override
    public String help() {
        return "<ol><li>Chat với <b>@BotFather</b>, gõ <code>/newbot</code> để lấy Bot Token.</li>"
                + "<li>Nhắn 1 tin bất kỳ cho bot vừa tạo.</li>"
                + "<li>Mở <code>https://api.telegram.org/bot&lt;TOKEN&gt;/getUpdates</code> và lấy số <code>chat.id</code>.</li>"
                + "<li><b>Gửi cho nhiều người:</b> mỗi người nhắn 1 tin cho bot rồi lấy <code>chat.id</code> của họ, nhập tất cả "
                + "cách nhau bằng dấu phẩy. Hoặc tạo một nhóm, thêm bot và mọi người vào, gửi 1 tin trong nhóm rồi lấy "
                + "<code>chat.id</code> của nhóm (số âm, dạng <code>-100…</code>).</li></ol>";
    }

    @Override
    public Map<String, String> validate(ObjectNode config) {
        Map<String, String> errors = new LinkedHashMap<>();
        String token = config.path("token").asString("").trim();
        String chat = config.path("chatId").asString("").trim();
        String tokenError = token.isEmpty() ? "Cần Bot Token để gửi tin nhắn" : Checks.checkTelegramToken(token);
        if (!tokenError.isEmpty()) errors.put("token", tokenError);
        String chatError = chat.isEmpty() ? "Cần ít nhất một Chat ID để biết gửi tin cho ai" : Checks.checkTelegramChats(chat);
        if (!chatError.isEmpty()) errors.put("chatId", chatError);
        config.put("token", token);
        config.put("chatId", String.join(", ", Checks.parseChatIds(chat))); // bỏ trùng, cách nhau ", "
        return errors;
    }

    /** Cho kiểm thử: trỏ tới máy chủ giả */
    public void setApiBase(String base) { this.apiBase = base; }

    /** Cho kiểm thử: thời gian chờ mỗi lần gọi */
    public void setTimeout(Duration d) {
        this.timeout = d;
        factory.setReadTimeout(d);
    }

    /** Gửi cho tất cả Chat ID (song song). Thiếu token hoặc Chat ID = chưa cài, không gửi gì. */
    @Override
    public SendResult send(Notice notice, JsonNode config) {
        String token = config.path("token").asString("");
        List<String> ids = chatIds(config.path("chatId").asString(""));
        if (token.isEmpty() || ids.isEmpty()) return SendResult.notConfigured();
        // nội dung đã là HTML rút gọn đúng kiểu Telegram hiểu, gửi nguyên văn
        List<Recipient> results = ids.parallelStream().map(id -> sendOne(token, id, notice.html())).toList();
        for (Recipient r : results) if (!r.ok()) log.warn("Telegram lỗi ({}): {}", r.id(), r.error());
        return new SendResult(true, results);
    }

    private Recipient sendOne(String token, String id, String text) {
        try {
            return http.post().uri(apiBase + "/bot" + token + "/sendMessage").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", id, "text", text, "parse_mode", "HTML"))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) return new Recipient(id, true, null, false);
                        JsonNode j;
                        try { j = mapper.readTree(res.getBody()); } catch (RuntimeException e) { j = mapper.createObjectNode(); }
                        int code = j.path("error_code").asInt(res.getStatusCode().value());
                        // che token phòng khi mô tả lỗi nhắc lại nó: kết quả này được đưa ra giao diện và nhật ký
                        String msg = friendly(code, j.path("description").asString(null)).replace(token, "***");
                        int status = res.getStatusCode().value();
                        return new Recipient(id, false, msg, status == 429 || status >= 500);
                    });
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause())
                if (t instanceof HttpTimeoutException)
                    return new Recipient(id, false, "Telegram không trả lời sau " + Math.round(timeout.toMillis() / 1000.0) + " giây.", true);
            return new Recipient(id, false, "Không kết nối được tới Telegram. Kiểm tra mạng internet.", true);
        }
    }

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
        if (code == 403 && Pattern.compile("not a member|kicked|write|rights|deactivated").matcher(d).find())
            return "Bot chưa ở trong nhóm/kênh này hoặc không có quyền gửi tin.";
        if (code == 429) return "Telegram đang giới hạn tốc độ gửi, thử lại sau ít phút.";
        return desc != null && !desc.isEmpty() ? "Telegram báo: " + desc : ("Lỗi Telegram " + (code == 0 ? "" : code)).trim();
    }
}
