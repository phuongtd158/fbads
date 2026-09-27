package com.fbads.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sự kiện realtime cho giao diện (WebSocket STOMP):
 *   publish("logs", dòng nhật ký) → Redis kênh "fbads:live" → MỌI bản tool nhận → gửi tới trình duyệt đang nghe "/topic/logs".
 * Đi qua Redis pub/sub để chạy nhiều bản tool thì người dùng nối vào bản nào cũng nhận được sự kiện do bản khác tạo.
 *
 * Các loại sự kiện: logs (dòng nhật ký mới/đã sửa), objects ({id}: camp/nhóm vừa đổi trạng thái hoặc ngân sách),
 * engine ({at}: xong một lượt tự động).
 */
@Service
public class LiveEvents {
    static final String CHANNEL = "fbads:live";
    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final SimpMessagingTemplate ws;

    public LiveEvents(StringRedisTemplate redis, JsonMapper json, SimpMessagingTemplate ws,
                      @Qualifier("redisListeners") RedisMessageListenerContainer listeners) {
        this.redis = redis;
        this.json = json;
        this.ws = ws;
        listeners.addMessageListener((message, pattern) -> deliver(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(CHANNEL));
    }

    /** Gửi sự kiện. Redis lỗi thì bỏ qua: giao diện vẫn tự làm mới định kỳ nên chỉ chậm hơn, không mất dữ liệu. */
    public void publish(String type, Object data) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(Map.of("type", type, "data", data)));
        } catch (RuntimeException e) {
            log.warn("Không gửi được sự kiện realtime ({}): {}", type, e.getMessage());
        }
    }

    /** Nhận từ Redis → đẩy xuống trình duyệt đang nối vào bản tool này */
    private void deliver(String raw) {
        try {
            JsonNode msg = json.readTree(raw);
            ws.convertAndSend("/topic/" + msg.get("type").asString(), json.writeValueAsString(msg.get("data")));
        } catch (RuntimeException e) {
            log.warn("Sự kiện realtime hỏng: {}", e.getMessage());
        }
    }
}
