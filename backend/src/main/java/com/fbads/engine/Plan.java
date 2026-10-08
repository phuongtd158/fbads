package com.fbads.engine;

import com.fbads.entity.LogChange;

/**
 * Kế hoạch cho một hành động (tính trước, chưa gọi Facebook). Xem PlanKind.
 */
public record Plan(PlanKind kind, String detail, LogChange after, double next, boolean capped, String code,
        String reason, boolean notifyOnly) {
    static Plan noop() { return new Plan(PlanKind.NOOP, null, null, 0, false, null, null, false); }

    static Plan skip(String code, String reason) { return new Plan(PlanKind.SKIP, null, null, 0, false, code, reason, false); }

    static Plan error(String message) { return new Plan(PlanKind.ERROR, message, null, 0, false, null, message, false); }

    static Plan doIt(String detail, LogChange after, double next, boolean capped) {
        return new Plan(PlanKind.DO, detail, after, next, capped, null, null, false);
    }

    static Plan notifyIt(String detail) { return new Plan(PlanKind.DO, detail, null, 0, false, null, null, true); }

    public boolean is(PlanKind k) { return kind == k; }
}
