package com.fbads.rule;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fbads.engine.PlanKind;

import java.util.Locale;

/** Kết quả xét một rule cho một camp (hiện ở Xem trước, JSON là chữ thường: "match", "nomatch"…). */
public enum DecisionStatus {
    /** Khớp và sẽ làm */
    MATCH,
    /** Không khớp điều kiện */
    NOMATCH,
    /** Bỏ qua có lý do (không đang chạy, ngoài khung giờ, đang nghỉ…) */
    SKIP,
    /** Khớp nhưng không có gì để đổi */
    NOCHANGE,
    /** Khớp nhưng kế hoạch lỗi */
    ERROR;

    @JsonValue
    public String code() { return name().toLowerCase(Locale.ROOT); }

    /** Trạng thái tương ứng với kế hoạch đã lập cho camp khớp điều kiện */
    static DecisionStatus of(PlanKind kind) {
        return switch (kind) {
            case DO -> MATCH;
            case SKIP -> SKIP;
            case ERROR -> ERROR;
            case NOOP -> NOCHANGE;
        };
    }
}
