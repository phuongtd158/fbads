package com.fbads.account;

/** Đổi mật khẩu đăng nhập */
public record PasswordChange(String currentPassword, @StrongPassword String newPassword) {}
