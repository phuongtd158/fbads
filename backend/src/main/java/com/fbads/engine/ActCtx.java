package com.fbads.engine;

import java.util.Map;

/** Ngữ cảnh của một lần thực thi: nguồn (schedule/rule/system/manual), lịch/rule nào, điều kiện đã khớp, có gửi Telegram không */
public record ActCtx(String kind, String refId, String refName, Map<String, Object> condition, boolean silent, boolean noCap,
        boolean includeLearning) {
    public ActCtx(String kind, String refId, String refName, Map<String, Object> condition, boolean silent) {
        this(kind, refId, refName, condition, silent, false, false);
    }

    public static ActCtx of(String kind, String refId, String refName) { return new ActCtx(kind, refId, refName, null, false); }

    public ActCtx silenced() { return new ActCtx(kind, refId, refName, condition, true, noCap, includeLearning); }

    public ActCtx withCondition(Map<String, Object> c) { return new ActCtx(kind, refId, refName, c, silent, noCap, includeLearning); }

    /**
     * Rule tăng theo bậc: không áp giới hạn % thay đổi mỗi ngày (đã bắt buộc có trần ngân sách);
     * includeLearning = tăng cả nhóm đang học.
     */
    public ActCtx ladder(boolean includeLearning) { return new ActCtx(kind, refId, refName, condition, silent, true, includeLearning); }

    public boolean isRule() { return "rule".equals(kind); }
}
