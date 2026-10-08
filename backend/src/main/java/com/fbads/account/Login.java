package com.fbads.account;

import com.fbads.common.Json;

/** Đăng nhập. Bỏ trống username = "admin" (giao diện cũ chỉ gửi mật khẩu). */
public record Login(String username, String password) {
    public static final Login EMPTY = new Login(null, null);

    public Login { username = Json.str(username); password = Json.str(password); }
}
