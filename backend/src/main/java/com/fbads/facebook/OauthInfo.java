package com.fbads.facebook;

/** GET /api/fb/oauth: địa chỉ cần khai báo trong ứng dụng Meta, lỗi của lần đăng nhập Facebook gần nhất ("" = không có) */
public record OauthInfo(String redirectUri, String error) {}
