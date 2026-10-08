package com.fbads.engine;

/**
 * Một hành động lên camp/nhóm QC.
 * BUDGET: mode (theo %, cộng/trừ, đặt số), value, max/min (0 = không giới hạn); NOTIFY: message.
 */
public record Action(ActionType type, BudgetMode mode, double value, double max, double min, String message) {
    public static Action on() { return new Action(ActionType.ON, null, 0, 0, 0, null); }

    public static Action off() { return new Action(ActionType.OFF, null, 0, 0, 0, null); }

    public static Action notifyOnly() { return new Action(ActionType.NOTIFY, null, 0, 0, 0, null); }

    public static Action budget(BudgetMode mode, double value, double max, double min) {
        return new Action(ActionType.BUDGET, mode, value, max, min, null);
    }

    public Action withMessage(String m) { return new Action(type, mode, value, max, min, m); }

    public boolean isBudget() { return type == ActionType.BUDGET; }

    public boolean isNotify() { return type == ActionType.NOTIFY; }

    /** Bật hoặc tắt */
    public boolean isOnOff() { return type == ActionType.ON || type == ActionType.OFF; }
}
