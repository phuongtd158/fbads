package com.fbads.account;

import com.fbads.common.Json;

/** Thêm thành viên: password chỉ cần khi tạo tài khoản mới. role sai thì Role.parse trả null, service báo lỗi. */
public record MemberAdd(String username, String name, String password, String role) {
    public static final MemberAdd EMPTY = new MemberAdd(null, null, null, null);

    public MemberAdd { username = Json.str(username); name = Json.str(name); password = Json.str(password); role = Json.str(role); }
}
