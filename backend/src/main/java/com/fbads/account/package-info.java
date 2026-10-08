/**
 * Tài khoản: người dùng, workspace, thành viên và vai trò (Role), đăng nhập, đăng ký, đổi mật khẩu.
 * <p>
 * Đọc theo thứ tự: AuthController (đăng nhập, /auth) → AuthService → User, Workspace, WorkspaceMember →
 * WorkspaceController (đổi workspace, thêm thành viên). LoginAttempts khoá đăng nhập sai nhiều lần (Redis).
 * Phần Spring Security (bộ lọc, chọn workspace cho mỗi request) nằm ở package security.
 */
package com.fbads.account;
