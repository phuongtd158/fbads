package com.fbads.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gửi sự kiện lên Kafka trên một luồng riêng, để nơi phát (engine, request HTTP) không bao giờ phải chờ Kafka.
 *  - Một luồng: sự kiện lên Kafka đúng thứ tự đã phát.
 *  - Kafka không chạy: mỗi lần gửi chờ tối đa max.block.ms (application.yml) rồi bỏ, chỉ luồng này phải chờ.
 *    Hàng đợi đầy ({@value #QUEUE} sự kiện) thì bỏ sự kiện mới. Dữ liệu đã nằm trong DB, mất sự kiện chỉ là mất thông báo/cập nhật tức thì.
 *  - Log: chỉ ghi lần lỗi đầu tiên và lúc Kafka nhận lại (kèm số sự kiện đã mất), không ghi từng sự kiện.
 */
class KafkaEventSender implements EventTransport, DisposableBean {
    static final int QUEUE = 1000;
    private static final Logger log = LoggerFactory.getLogger(KafkaEventSender.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE), r -> {
                Thread t = new Thread(r, "kafka-events");
                t.setDaemon(true);
                return t;
            });
    /** Số sự kiện lỗi liên tiếp, về 0 khi Kafka nhận lại */
    private final AtomicLong failed = new AtomicLong();

    KafkaEventSender(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void send(AppEvent e) {
        String payload = json.writeValueAsString(e);
        try {
            executor.execute(() -> deliver(e, payload));
        } catch (RejectedExecutionException ex) {
            failed(e, executor.isShutdown() ? "ứng dụng đang tắt" : "hàng đợi gửi Kafka đầy (" + QUEUE + " sự kiện)");
        }
    }

    private void deliver(AppEvent e, String payload) {
        try {
            kafka.send(EventTopics.EVENTS, e.key(), payload).whenComplete((r, ex) -> {
                if (ex != null) failed(e, ex.getMessage());
                else sent();
            });
        } catch (RuntimeException ex) {
            failed(e, ex.getMessage());
        }
    }

    private void failed(AppEvent e, String why) {
        if (failed.incrementAndGet() == 1) {
            log.warn("Kafka không nhận sự kiện {} ({}): {}. Các sự kiện lỗi tiếp theo không ghi log từng cái", e.type(), e.id(), why);
        } else {
            log.debug("Kafka không nhận sự kiện {} ({}): {}", e.type(), e.id(), why);
        }
    }

    private void sent() {
        long lost = failed.getAndSet(0);
        if (lost > 0) log.info("Kafka nhận sự kiện trở lại (đã mất {} sự kiện trong lúc lỗi)", lost);
    }

    /** Tắt ứng dụng: gửi nốt hàng đợi (tối đa 5 giây) trước khi Spring đóng producer */
    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            int left = executor.shutdownNow().size();
            log.warn("Tắt ứng dụng: bỏ {} sự kiện chưa gửi được lên Kafka", left);
        }
    }
}
