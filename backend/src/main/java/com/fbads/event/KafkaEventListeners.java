package com.fbads.event;

import com.fbads.service.EventStatsService;
import com.fbads.service.LiveEvents;
import com.fbads.service.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Các consumer đọc topic fbads.events khi bật Kafka. Mỗi consumer một group id riêng: Kafka giao mọi bản ghi
 * cho từng group, nên thêm consumer mới không ảnh hưởng consumer cũ.
 *
 *  - fbads-telegram: gửi Telegram. Lỗi tạm thời → bản ghi chuyển sang topic thử lại fbads.events-telegram-retry-0, -1…
 *    (chờ tăng dần), hết lượt → fbads.events-telegram-dlt. Lỗi cố định (token/chat id sai) → vào thẳng DLT.
 *    Thử lại bằng topic riêng nên một tin lỗi không chặn các tin sau.
 *  - fbads-stats: đếm thao tác theo ngày. Nhiều bản tool dùng chung group này → mỗi sự kiện chỉ đếm một lần.
 *  - fbads-live-{ngẫu nhiên}: đẩy xuống trình duyệt. Mỗi bản tool một group riêng → bản nào cũng nhận đủ,
 *    vì trình duyệt có thể nối vào bất kỳ bản nào. Chỉ đọc sự kiện mới (latest): sự kiện cũ không còn ý nghĩa với giao diện.
 */
@Component
@ConditionalOnProperty(name = "fbads.kafka.enabled", havingValue = "true")
public class KafkaEventListeners {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventListeners.class);

    private final TelegramNotifier telegram;
    private final EventStatsService stats;
    private final LiveEvents live;
    private final JsonMapper json;

    public KafkaEventListeners(TelegramNotifier telegram, EventStatsService stats, LiveEvents live, JsonMapper json) {
        this.telegram = telegram;
        this.stats = stats;
        this.live = live;
        this.json = json;
    }

    /** Bản ghi không đọc được thì thử lại cũng vô ích → vào thẳng DLT (Telegram) hoặc bỏ qua (consumer khác) */
    static class BadEventException extends RuntimeException {
        BadEventException(Throwable cause) { super("Sự kiện không đọc được: " + cause.getMessage(), cause); }
    }

    private AppEvent parse(String payload) {
        try {
            return json.readValue(payload, AppEvent.class);
        } catch (RuntimeException e) {
            throw new BadEventException(e);
        }
    }

    @RetryableTopic(
            attempts = "${fbads.kafka.telegram.attempts:4}",
            backOff = @BackOff(delayString = "${fbads.kafka.telegram.backoff-ms:5000}", multiplier = 3.0, maxDelay = 300_000),
            retryTopicSuffix = EventTopics.TELEGRAM_RETRY_SUFFIX,
            dltTopicSuffix = EventTopics.TELEGRAM_DLT_SUFFIX,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = {TelegramNotifier.PermanentFailure.class, BadEventException.class},
            numPartitions = "1",
            replicationFactor = "1")
    @KafkaListener(id = "fbads-telegram", groupId = "fbads-telegram", topics = EventTopics.EVENTS)
    public void telegram(String payload) {
        telegram.handle(parse(payload));
    }

    /** Tin Telegram đã hết lượt thử (hoặc lỗi cố định): nằm lại ở topic DLT để xem sau, ở đây chỉ ghi log */
    @DltHandler
    public void telegramFailed(String payload, @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        log.error("Bỏ tin Telegram sau khi thử lại không được: {} — sự kiện: {}", error, payload);
    }

    @KafkaListener(id = "fbads-stats", groupId = "fbads-stats", topics = EventTopics.EVENTS)
    public void stats(String payload) {
        stats.handle(parse(payload));
    }

    @KafkaListener(id = "fbads-live", groupId = "fbads-live-#{T(java.util.UUID).randomUUID()}", topics = EventTopics.EVENTS,
            properties = "auto.offset.reset=latest")
    public void live(String payload) {
        live.deliver(parse(payload));
    }
}
