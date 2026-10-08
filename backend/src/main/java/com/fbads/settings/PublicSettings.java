package com.fbads.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Cài đặt gửi về giao diện: mọi trường của AppSettings, riêng bí mật (token, App Secret) luôn để trống và thay bằng
 * cờ has_… (đã nhập chưa); has_notify = có kênh thông báo nào đang bật.
 */
public record PublicSettings(@JsonUnwrapped AppSettings settings, String accessToken, String fbAppSecret,
        @JsonProperty("has_accessToken") boolean hasAccessToken, @JsonProperty("has_fbAppSecret") boolean hasFbAppSecret,
        @JsonProperty("has_notify") boolean hasNotify) {
    public static PublicSettings of(AppSettings s, boolean hasNotify) {
        return new PublicSettings(s, "", "", !s.getAccessToken().isEmpty(), !nullToEmpty(s.getFbAppSecret()).isEmpty(),
                hasNotify);
    }

    private static String nullToEmpty(String v) { return v == null ? "" : v; }
}
