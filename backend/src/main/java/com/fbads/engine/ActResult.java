package com.fbads.engine;

/** Kết quả thực thi một hành động (ActionExecutor.act). */
public enum ActResult {
    /** Đã đúng sẵn */
    NOOP,
    /** Bỏ qua có lý do */
    SKIP,
    /** Kế hoạch lỗi, không gọi Facebook */
    ERROR,
    /** Đã làm (hoặc chạy thử) */
    OK,
    /** Gọi Facebook bị lỗi */
    FAIL
}
