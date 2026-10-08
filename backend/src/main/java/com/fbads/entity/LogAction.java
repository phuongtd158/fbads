package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.JsonConverters;
import com.fbads.engine.Action;
import com.fbads.engine.ActionType;
import com.fbads.engine.BudgetMode;

/**
 * Nhật ký: đã làm gì. Đổi ngân sách thì có mode/value (max/min nếu có giới hạn); revert = đây là lần hoàn tác.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogAction(ActionType type, BudgetMode mode, Double value, Double max, Double min, Boolean revert) {
    public static LogAction of(Action a) {
        if (!a.isBudget()) return new LogAction(a.type(), null, null, null, null, null);
        return new LogAction(a.type(), a.mode(), a.value(), a.max() != 0 ? a.max() : null, a.min() != 0 ? a.min() : null, null);
    }

    /** Thao tác tay: bật/tắt */
    public static LogAction status(boolean on) { return new LogAction(on ? ActionType.ON : ActionType.OFF, null, null, null, null, null); }

    /** Thao tác tay: đặt ngân sách đúng số tiền */
    public static LogAction setBudget(double value) {
        return new LogAction(ActionType.BUDGET, BudgetMode.SET, value, null, null, null);
    }

    /** Cùng thao tác, đánh dấu là hoàn tác */
    public LogAction reverted() { return new LogAction(type, mode, value, max, min, true); }

    /** Bật, tắt, đổi ngân sách: những việc có thay đổi thật trên Facebook (hoàn tác được, hiện trên biểu đồ) */
    public boolean changesObject() { return type == ActionType.ON || type == ActionType.OFF || type == ActionType.BUDGET; }

    public static class Converter extends JsonConverters.Of<LogAction> {
        public Converter() { super(LogAction.class); }
    }
}
