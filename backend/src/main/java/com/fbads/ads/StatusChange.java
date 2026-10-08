package com.fbads.ads;

/** Bật/tắt 1 camp hoặc nhóm QC. name chỉ để ghi nhật ký, có thể null. */
public record StatusChange(Boolean on, String name) {
    public static final StatusChange EMPTY = new StatusChange(null, null);

    public StatusChange { on = Boolean.TRUE.equals(on); }
}
