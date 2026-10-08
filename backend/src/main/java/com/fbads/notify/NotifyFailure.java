package com.fbads.notify;

/**
 * Gửi thông báo không được. Hai loại, để nơi gọi biết có nên thử lại không:
 * Kafka thử lại {@link Retryable}, còn {@link Permanent} thì đưa thẳng vào DLT.
 */
public abstract class NotifyFailure extends RuntimeException {
    protected NotifyFailure(String message) { super(message); }

    /** Lỗi tạm thời (mất mạng, dịch vụ quá tải/lỗi server): thử lại sau có thể được */
    public static class Retryable extends NotifyFailure {
        public Retryable(String message) { super(message); }
    }

    /** Lỗi cố định (token sai, người nhận sai, bị chặn): thử lại vô ích */
    public static class Permanent extends NotifyFailure {
        public Permanent(String message) { super(message); }
    }
}
