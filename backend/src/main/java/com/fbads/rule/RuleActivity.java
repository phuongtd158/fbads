package com.fbads.rule;

/** Hoạt động 7 ngày của một rule: số lần tác động, số lần lỗi, lần gần nhất, số mục đang chờ bật lại */
public record RuleActivity(int acts, int errors, LastRun last, int resumePending) {
    /** Lần chạy gần nhất của một rule (theo nhật ký) */
    public record LastRun(String ts, String name, String detail, boolean ok, boolean dry) {}
}
