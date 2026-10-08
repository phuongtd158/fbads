package com.fbads.account;

/** Chọn workspace làm việc */
public record WorkspaceSwitch(Long id) {
    public static final WorkspaceSwitch EMPTY = new WorkspaceSwitch(null);

    public WorkspaceSwitch { id = id == null ? 0L : id; }
}
