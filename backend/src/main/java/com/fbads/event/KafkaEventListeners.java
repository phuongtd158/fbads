package com.fbads.event;

import com.fbads.notify.Notifier;
import com.fbads.notify.NotifyFailure;
import com.fbads.service.EventStatsService;
import com.fbads.service.LiveEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
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
 *  - fbads-telegram: gửi thông báo, mỗi sự kiện mỗi kênh một lần (Notifier.handleOnce). Lỗi tạm thời → bản ghi chuyển sang
 *    topic thử lại fbads.events-telegram-retry-0, -1… (chờ tăng dần), hết lượt → fbads.events-telegram-dlt.
 *    Lỗi cố định (token/chat id sai) hoặc bản ghi hỏng → vào thẳng DLT.
 *    Thử lại bằng topic riêng nên một tin lỗi không chặn các tin sau; đổi lại, tin được thử lại sẽ đến sau các tin phát sau nó.
 *  - fbads-stats: đếm thao tác theo ngày. Nhiều bản tool dùng chung group này → mỗi sự kiện chỉ đếm một lần.
 *  - fbads-live-{ngẫu nhiên}: đẩy xuống trình duyệt. Mỗi bản tool một group riêng → bản nào cũng nhận đủ,
 *    vì trình duyệt có thể nối vào bất kỳ bản nào. Chỉ đọc sự kiện mới (latest) và không lưu vị trí đã đọc:
 *    sự kiện cũ không còn ý nghĩa với giao diện.
 */
@Component
@Conditional(KafkaMode.On.class)
public class KafkaEventListeners {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventListeners.class);

    private final Notifier notifier;
    private final EventStatsService stats;
    private final LiveEvents live;
    private final JsonMapper json;

    public KafkaEventListeners(Notifier notifier, EventStatsService stats, LiveEvents live, JsonMapper json) {
        this.notifier = notifier;
        this.stats = stats;
        this.live = live;
        this.json = json;
    }

    /** Bản ghi không đọc được: thử lại cũng vô ích → vào thẳng DLT (Telegram) hoặc bỏ qua (thống kê, WebSocket) */
    static class BadEventException extends RuntimeException {
        BadEventException(String why, Throwable cause) { super("Sự kiện không đọc được: " + why, cause); }
    }

    AppEvent parse(String payload) {
        AppEvent e;
        try {
            e = json.readValue(payload, AppEvent.class);
        } catch (RuntimeException ex) {
            throw new BadEventException(ex.getMessage(), ex);
        }
        if (e == null || e.id() == null || e.type() == null) throw new BadEventException("thiếu id hoặc type", null);
        return e;
    }

    @RetryableTopic(
            attempts = "${fbads.kafka.telegram.attempts:4}",
            backOff = @BackOff(delayString = "${fbads.kafka.telegram.backoff-ms:5000}", multiplier = 3.0, maxDelay = 300_000),
            retryTopicSuffix = EventTopics.TELEGRAM_RETRY_SUFFIX,
            dltTopicSuffix = EventTopics.TELEGRAM_DLT_SUFFIX,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = {NotifyFailure.Permanent.class, BadEventException.class},
            numPartitions = EventTopics.PARTITIONS + "",
            replicationFactor = "1")
    // RECORD: báo đã đọc sau từng tin, tool chết giữa chừng thì ít tin phải đọc lại (tin đã gửi vẫn không gửi lại, xem handleOnce)
    @KafkaListener(id = "fbads-telegram", groupId = "fbads-telegram", topics = EventTopics.EVENTS, ackMode = "RECORD")
    public void telegram(String payload) {
        AppEvent e = parse(payload);
        e.runInWorkspace(() -> notifier.handleOnce(e));
    }

    /** Tin Telegram đã hết lượt thử (hoặc lỗi cố định): nằm lại ở topic DLT để xem sau, ở đây chỉ ghi log (không kèm nội dung tin) */
    @DltHandler
    public void telegramFailed(String payload, @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        String what;
        try {
            AppEvent e = parse(payload);
            what = e.type() + " " + e.id();
        } catch (BadEventException ex) {
            what = "hỏng, " + payload.length() + " byte";
        }
        log.error("Bỏ tin Telegram (sự kiện {}) sau khi thử lại không được: {}. Bản ghi còn ở topic {}", what, error,
                EventTopics.TELEGRAM_DLT);
    }

    @KafkaListener(id = "fbads-stats", groupId = "fbads-stats", topics = EventTopics.EVENTS)
    public void stats(String payload) {
        AppEvent e = parse(payload);
        e.runInWorkspace(() -> stats.handle(e));
    }

    /**
     * Lỗi thì chỉ ghi log: đẩy lại một cập nhật giao diện cũ là vô ích (giao diện vẫn tự làm mới định kỳ).
     * MANUAL mà không bao giờ báo đã đọc + neverCommit (KafkaEvents): group này không lưu vị trí nào.
     */
    @KafkaListener(id = "fbads-live", groupId = "fbads-live-#{T(java.util.UUID).randomUUID()}", topics = EventTopics.EVENTS,
            properties = "auto.offset.reset=latest", ackMode = "MANUAL", containerPostProcessor = "neverCommit")
    public void live(String payload) {
        try {
            live.deliver(parse(payload));
        } catch (RuntimeException ex) {
            log.warn("Không đẩy được sự kiện xuống giao diện: {}", ex.getMessage());
        }
    }
}
