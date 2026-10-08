package com.fbads.engine;

/** Loại kế hoạch cho một hành động (xem Plan). */
public enum PlanKind {
    /** Đã đúng sẵn, không cần làm gì */
    NOOP,
    /** Bỏ qua có lý do (đang học, chạm giới hạn ngày…) */
    SKIP,
    /** Không làm được (vd ngân sách mới ≤ 0) */
    ERROR,
    /** Sẽ thực hiện */
    DO
}
