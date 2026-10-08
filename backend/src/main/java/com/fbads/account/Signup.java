package com.fbads.account;

import com.fbads.common.Json;

/** Tạo tài khoản đầu tiên (/setup) hoặc tự đăng ký (/register, thêm tên workspace) */
public record Signup(String username, String name, String password, String workspaceName) {
    public static final Signup EMPTY = new Signup(null, null, null, null);

    public Signup { username = Json.str(username); name = Json.str(name); password = Json.str(password); workspaceName = Json.str(workspaceName); }
}
