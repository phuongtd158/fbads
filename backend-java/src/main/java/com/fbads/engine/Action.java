package com.fbads.engine;

/**
 * Một hành động lên camp/nhóm QC.
 * type: on | off | budget | notify; budget: mode = percent | add | set, value, max/min (0 = không giới hạn); notify: message.
 */
public record Action(String type, String mode, double value, double max, double min, String message) {
    public static Action on() { return new Action("on", null, 0, 0, 0, null); }

    public static Action off() { return new Action("off", null, 0, 0, 0, null); }

    public Action withMessage(String m) { return new Action(type, mode, value, max, min, m); }

    public boolean isBudget() { return "budget".equals(type); }

    public boolean isNotify() { return "notify".equals(type); }
}
