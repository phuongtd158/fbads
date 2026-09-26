package com.fbads.engine;

import java.util.Map;

/** Ngữ cảnh của một lần thực thi: nguồn (schedule/rule/system/manual), lịch/rule nào, điều kiện đã khớp, có gửi Telegram không */
public record ActCtx(String kind, String refId, String refName, Map<String, Object> condition, boolean silent) {
    public static ActCtx of(String kind, String refId, String refName) { return new ActCtx(kind, refId, refName, null, false); }

    public ActCtx silenced() { return new ActCtx(kind, refId, refName, condition, true); }

    public ActCtx withCondition(Map<String, Object> c) { return new ActCtx(kind, refId, refName, c, silent); }

    public boolean isRule() { return "rule".equals(kind); }
}
