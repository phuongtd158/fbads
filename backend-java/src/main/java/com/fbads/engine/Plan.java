package com.fbads.engine;

import java.util.Map;

/**
 * Kế hoạch cho một hành động (tính trước, chưa gọi Facebook).
 * kind: noop (đã đúng sẵn) | skip (bỏ qua có lý do) | error | do (sẽ thực hiện).
 */
public record Plan(String kind, String detail, Map<String, Object> after, double next, boolean capped, String code, String reason, boolean notifyOnly) {
    static Plan noop() { return new Plan("noop", null, null, 0, false, null, null, false); }

    static Plan skip(String code, String reason) { return new Plan("skip", null, null, 0, false, code, reason, false); }

    static Plan error(String message) { return new Plan("error", message, null, 0, false, null, message, false); }

    static Plan doIt(String detail, Map<String, Object> after, double next, boolean capped) { return new Plan("do", detail, after, next, capped, null, null, false); }

    static Plan notifyIt(String detail) { return new Plan("do", detail, null, 0, false, null, null, true); }

    public boolean is(String k) { return kind.equals(k); }
}
