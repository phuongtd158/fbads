package com.fbads.event;

import com.fbads.service.EventStatsService;
import com.fbads.service.LiveEvents;
import com.fbads.service.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * KAFKA_ENABLED khác "true" (mặc định false, và trên Render): sự kiện đi bằng Spring events trong cùng ứng dụng.
 * Các consumer chạy ngay trên luồng vừa phát, lần lượt: WebSocket → thống kê → Telegram.
 * Mỗi consumer tự bắt lỗi của mình, để một consumer lỗi không chặn các consumer sau (và không làm hỏng việc đang phát).
 */
@Configuration
@Conditional(KafkaMode.Off.class)
public class LocalEvents {
    private static final Logger log = LoggerFactory.getLogger(LocalEvents.class);

    private final LiveEvents live;
    private final EventStatsService stats;
    private final TelegramNotifier telegram;

    public LocalEvents(LiveEvents live, EventStatsService stats, TelegramNotifier telegram,
                       @Qualifier("redisListeners") RedisMessageListenerContainer redisListeners) {
        this.live = live;
        this.stats = stats;
        this.telegram = telegram;
        live.listenRedis(redisListeners); // WebSocket: nhiều bản tool thì sự kiện đi qua Redis pub/sub
    }

    @Bean
    EventTransport springEventTransport(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @EventListener
    @Order(1)
    public void live(AppEvent e) { live.broadcast(e); }

    @EventListener
    @Order(2)
    public void stats(AppEvent e) {
        try {
            e.runInWorkspace(() -> stats.handle(e));
        } catch (RuntimeException ex) {
            log.warn("Không ghi được thống kê cho sự kiện {}: {}", e.id(), ex.getMessage());
        }
    }

    /** Không Kafka thì không có hàng đợi để thử lại: gửi lỗi chỉ ghi log (như trước khi có Kafka) */
    @EventListener
    @Order(3)
    public void telegram(AppEvent e) {
        try {
            e.runInWorkspace(() -> telegram.handle(e));
        } catch (TelegramNotifier.RetryableFailure | TelegramNotifier.PermanentFailure ex) {
            // TelegramService đã ghi log lý do
        } catch (RuntimeException ex) {
            log.warn("Không gửi được Telegram cho sự kiện {}: {}", e.id(), ex.getMessage());
        }
    }
}
