package com.fbads.service;

import com.fbads.event.AppEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * Consumer "Telegram": nhận sự kiện, sự kiện nào cần báo thì soạn tin và gửi.
 *  - log.created có telegram=true (lịch, rule, dừng khẩn, hoàn tác): một dòng tóm tắt, giống bản Node;
 *  - report.daily: báo cáo hằng ngày; telegram.text: tin soạn sẵn (cảnh báo, báo cáo tuần…).
 * Gửi không được thì ném lỗi để nơi gọi quyết định: Kafka thử lại (lỗi tạm thời) hoặc đưa vào DLT (lỗi cố định);
 * chế độ không Kafka thì chỉ ghi log.
 */
@Service
public class TelegramNotifier {
    /** Lỗi tạm thời (mất mạng, Telegram quá tải/lỗi server): thử lại sau có thể được */
    public static class RetryableFailure extends RuntimeException {
        public RetryableFailure(String message) { super(message); }
    }

    /** Lỗi cố định (token sai, chat id sai, bot bị chặn): thử lại vô ích */
    public static class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) { super(message); }
    }

    /** Dấu "đã gửi" của từng sự kiện trong Redis: fbads:tg:{id}, giữ 2 ngày */
    static final String SENT_PREFIX = "fbads:tg:";
    private static final Duration SENT_KEEP = Duration.ofDays(2);
    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramService telegram;
    private final StringRedisTemplate redis;

    public TelegramNotifier(TelegramService telegram, StringRedisTemplate redis) {
        this.telegram = telegram;
        this.redis = redis;
    }

    public void handle(AppEvent e) {
        String text = textOf(e);
        if (text != null) send(text);
    }

    /**
     * Như handle(), nhưng mỗi sự kiện chỉ gửi một lần dù Kafka giao lại (tool chết trước khi kịp báo đã đọc…).
     * Đặt dấu "đã gửi" trong Redis trước khi gửi (SET NX); gửi lỗi thì xoá dấu để lượt thử lại gửi được.
     * Redis lỗi thì vẫn gửi: thà trùng tin còn hơn mất tin.
     */
    public void handleOnce(AppEvent e) {
        String text = textOf(e);
        if (text == null) return;
        String key = SENT_PREFIX + e.id();
        if (!claim(key)) {
            log.info("Bỏ qua tin Telegram đã gửi (sự kiện {})", e.id());
            return;
        }
        try {
            send(text);
        } catch (RuntimeException ex) {
            release(key);
            throw ex;
        }
    }

    private boolean claim(String key) {
        try {
            return !Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(key, "1", SENT_KEEP));
        } catch (RuntimeException ex) {
            log.warn("Không kiểm tra được tin Telegram đã gửi chưa (Redis lỗi), vẫn gửi: {}", ex.getMessage());
            return true;
        }
    }

    private void release(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Không xoá được dấu đã gửi {} (Redis lỗi), lượt thử lại sẽ bỏ qua tin này: {}", key, ex.getMessage());
        }
    }

    private void send(String text) {
        TelegramService.SendResult r = telegram.send(text);
        // chưa cài Telegram, hoặc ít nhất một người đã nhận: xong (không gửi lại cho người đã nhận)
        if (!r.configured() || r.anyOk()) return;
        String why = r.results().getFirst().error();
        if (r.results().stream().anyMatch(TelegramService.Result::retryable)) throw new RetryableFailure(why);
        throw new PermanentFailure(why);
    }

    /** Nội dung tin cho sự kiện, null = sự kiện này không cần báo */
    static String textOf(AppEvent e) {
        if (!e.telegram() || e.data() == null) return null;
        return switch (e.type()) {
            case AppEvent.LOG_CREATED -> logLine(e.data());
            case AppEvent.DAILY_REPORT, AppEvent.TELEGRAM_TEXT -> e.data().path("text").asString(null);
            default -> null;
        };
    }

    /** "✅ <b>Nguồn</b>\nTên: chi tiết" — icon: ❌ lỗi, 🔔 cảnh báo, ⏭️ bỏ qua, 🧪 chạy thử, ✅ thành công */
    static String logLine(JsonNode l) {
        boolean ok = !l.path("ok").isBoolean() || l.path("ok").asBoolean();
        boolean isNotify = "notify".equals(l.path("action").path("type").asString(null));
        boolean dry = l.path("dry").asBoolean(false);
        boolean skipped = l.path("skipped").asBoolean(false);
        String icon = !ok ? "❌" : isNotify ? "🔔" : skipped ? "⏭️" : dry ? "🧪" : "✅";
        String tag = dry && !isNotify ? " (chạy thử)" : "";
        return icon + " <b>" + str(l, "source") + "</b>" + tag + "\n" + str(l, "name") + ": " + str(l, "detail");
    }

    /** Như nối chuỗi Java trước đây: trường trống in ra "null" */
    private static String str(JsonNode l, String field) {
        JsonNode v = l.get(field);
        return v == null || v.isNull() ? "null" : v.asString();
    }
}
