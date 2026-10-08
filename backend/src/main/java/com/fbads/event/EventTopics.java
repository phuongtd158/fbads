package com.fbads.event;

/** Tên topic Kafka. Topic thử lại / DLT của consumer thông báo do @RetryableTopic tự tạo (xem KafkaEventListeners). */
public final class EventTopics {
    private EventTopics() {}

    /** Mọi sự kiện của tool */
    public static final String EVENTS = "fbads.events";
    /** Số partition của fbads.events, và của topic thử lại/DLT (giữ bằng nhau: bản ghi chuyển sang đúng partition cùng số) */
    public static final int PARTITIONS = 3;
    /** Hậu tố topic thử lại của consumer thông báo: fbads.events-notify-retry-0, -1, … */
    public static final String NOTIFY_RETRY_SUFFIX = "-notify-retry";
    /** Hậu tố topic lỗi (dead letter) của consumer thông báo: fbads.events-notify-dlt */
    public static final String NOTIFY_DLT_SUFFIX = "-notify-dlt";
    public static final String NOTIFY_DLT = EVENTS + NOTIFY_DLT_SUFFIX;
}
