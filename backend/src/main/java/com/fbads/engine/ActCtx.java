package com.fbads.engine;

import com.fbads.entity.LogKind;

import java.util.Map;

/** Ngữ cảnh của một lần thực thi: nguồn (lịch/rule/hệ thống/thủ công), lịch/rule nào, điều kiện đã khớp, có gửi Telegram không */
public record ActCtx(LogKind kind, String refId, String refName, Map<String, Object> condition, boolean silent, boolean noCap,
        boolean includeLearning) {
    public ActCtx(LogKind kind, String refId, String refName, Map<String, Object> condition, boolean silent) {
        this(kind, refId, refName, condition, silent, false, false);
    }

    public static ActCtx of(LogKind kind, String refId, String refName) { return new ActCtx(kind, refId, refName, null, false); }

    /** Dừng khẩn và các việc hệ thống tự làm: không gửi Telegram từng mục */
    public static ActCtx system() { return new ActCtx(LogKind.SYSTEM, null, null, null, true); }

    public ActCtx silenced() { return new ActCtx(kind, refId, refName, condition, true, noCap, includeLearning); }

    public ActCtx withCondition(Map<String, Object> c) { return new ActCtx(kind, refId, refName, c, silent, noCap, includeLearning); }

    /**
     * Rule tăng theo bậc: không áp giới hạn % thay đổi mỗi ngày (đã bắt buộc có trần ngân sách);
     * includeLearning = tăng cả nhóm đang học.
     */
    public ActCtx ladder(boolean includeLearning) { return new ActCtx(kind, refId, refName, condition, silent, true, includeLearning); }

    public boolean isRule() { return kind == LogKind.RULE; }
}
