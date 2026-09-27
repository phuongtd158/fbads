package com.fbads.entity;

/**
 * Vai trò trong một workspace, quyền tăng dần:
 *  - VIEWER: chỉ xem;
 *  - EDITOR: thêm/sửa lịch, rule, bật/tắt camp, đổi ngân sách, hoàn tác;
 *  - OWNER : thêm cả cài đặt, token Facebook, Telegram, thành viên.
 */
public enum Role {
    VIEWER, EDITOR, OWNER;

    public boolean atLeast(Role r) { return compareTo(r) >= 0; }

    /** "owner" / "OWNER" → OWNER; sai thì null */
    public static Role parse(String s) {
        if (s == null) return null;
        for (Role r : values()) if (r.name().equalsIgnoreCase(s.trim())) return r;
        return null;
    }
}
