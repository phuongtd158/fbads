package com.fbads.account;

import java.util.List;

/**
 * GET /api/auth: required = cần đăng nhập, authed = đã đăng nhập, setup = chưa có tài khoản nào, signup = được tự đăng
 * ký, envManaged = tài khoản quản trị đặt bằng biến môi trường, workspace đang chọn và mọi workspace của người này
 */
public record AuthStatus(boolean required, boolean authed, boolean setup, boolean signup, boolean envManaged, UserInfo user,
        AuthService.Membership workspace, List<AuthService.Membership> workspaces) {}
