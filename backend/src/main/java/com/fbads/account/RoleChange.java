package com.fbads.account;

import com.fbads.common.Json;

/** Đổi vai trò thành viên */
public record RoleChange(String role) {
    public static final RoleChange EMPTY = new RoleChange(null);

    public RoleChange { role = Json.str(role); }
}
