package com.fbads.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * Consumer "WebSocket": đẩy sự kiện xuống trình duyệt (STOMP) ở các kênh của từng workspace ({id} = id workspace):
 *   /topic/ws.{id}.logs    (log.created, log.updated: dòng nhật ký, giao diện thay theo id)
 *   /topic/ws.{id}.objects (objects.changed: {id} camp/nhóm vừa đổi)
 *   /topic/ws.{id}.engine  (engine.tick: {at} xong một lượt tự động).
 * Chỉ thành viên của workspace mới được nghe kênh của workspace đó (config/WebSocketConfig).
 * Trình duyệt có thể nối vào bất kỳ bản tool nào, nên mỗi bản đều phải nhận đủ sự kiện:
 *  - bật Kafka: mỗi bản đọc topic bằng một consumer group riêng (xem KafkaEventListeners) rồi gọi deliver();
 *  - tắt Kafka: broadcast() phát qua Redis pub/sub kênh "fbads:live", mọi bản nghe kênh đó rồi gọi deliver().
 */
@Service
public class LiveEvents {
    static final String CHANNEL = "fbads:live";
    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final SimpMessagingTemplate ws;

    public LiveEvents(StringRedisTemplate redis, JsonMapper json, SimpMessagingTemplate ws) {
        this.redis = redis;
        this.json = json;
        this.ws = ws;
    }

    /** Kênh STOMP của loại sự kiện, null = giao diện không cần */
    static String topicOf(String type) {
        if (type == null) return null;
        return switch (type) {
            case AppEvent.LOG_CREATED, AppEvent.LOG_UPDATED -> "logs";
            case AppEvent.OBJECTS_CHANGED -> "objects";
            case AppEvent.ENGINE_TICK -> "engine";
            default -> null;
        };
    }

    /** Kênh STOMP của một workspace */
    public static String destination(long workspaceId, String topic) { return "/topic/ws." + workspaceId + "." + topic; }

    /** Đẩy xuống trình duyệt đang nối vào bản tool này */
    public void deliver(AppEvent e) {
        String topic = topicOf(e.type());
        if (topic == null) return;
        ws.convertAndSend(destination(e.workspaceId(), topic), json.writeValueAsString(e.data()));
    }

    /** Chế độ không Kafka: phát qua Redis để mọi bản tool cùng nhận. Redis lỗi thì bỏ qua (giao diện vẫn tự làm mới định kỳ). */
    public void broadcast(AppEvent e) {
        if (topicOf(e.type()) == null) return;
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(e));
        } catch (RuntimeException ex) {
            log.warn("Không gửi được sự kiện realtime qua Redis ({}): {}", e.type(), ex.getMessage());
        }
    }

    /** Chế độ không Kafka: nghe kênh Redis, nhận được thì deliver() */
    public void listenRedis(RedisMessageListenerContainer listeners) {
        listeners.addMessageListener((message, pattern) -> {
            try {
                deliver(json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), AppEvent.class));
            } catch (RuntimeException ex) {
                log.warn("Sự kiện realtime hỏng: {}", ex.getMessage());
            }
        }, new ChannelTopic(CHANNEL));
    }
}
