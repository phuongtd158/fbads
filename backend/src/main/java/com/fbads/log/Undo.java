package com.fbads.log;

/** Hoàn tác 1 dòng nhật ký; force = hoàn tác dù camp đã bị đổi tay sau đó */
public record Undo(Boolean force) {
    public static final Undo EMPTY = new Undo(null);

    public Undo { force = Boolean.TRUE.equals(force); }
}
