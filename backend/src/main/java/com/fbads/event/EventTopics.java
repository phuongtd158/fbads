package com.fbads.event;

/** Tên topic Kafka. Topic thử lại / DLT của Telegram do @RetryableTopic tự tạo (xem KafkaEventListeners). */
public final class EventTopics {
    private EventTopics() {}

    /** Mọi sự kiện của tool */
    public static final String EVENTS = "fbads.events";
    /** Số partition của fbads.events, và của topic thử lại/DLT (giữ bằng nhau: bản ghi chuyển sang đúng partition cùng số) */
    public static final int PARTITIONS = 3;
    /** Hậu tố topic thử lại của consumer Telegram: fbads.events-telegram-retry-0, -1, … */
    public static final String TELEGRAM_RETRY_SUFFIX = "-telegram-retry";
    /** Hậu tố topic lỗi (dead letter) của consumer Telegram: fbads.events-telegram-dlt */
    public static final String TELEGRAM_DLT_SUFFIX = "-telegram-dlt";
    public static final String TELEGRAM_DLT = EVENTS + TELEGRAM_DLT_SUFFIX;
}
