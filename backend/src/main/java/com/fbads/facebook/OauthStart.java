package com.fbads.facebook;

import com.fbads.common.Json;

/**
 * Bắt đầu đăng nhập bằng Facebook. appId/appSecret bỏ trống = dùng cái đã lưu.
 * configId: null = không gửi (dùng cái đã lưu), "" = bỏ Configuration ID.
 */
public record OauthStart(String appId, String appSecret, String configId) {
    public static final OauthStart EMPTY = new OauthStart(null, null, null);

    public OauthStart { appId = Json.trimmed(appId); appSecret = Json.trimmed(appSecret); configId = configId == null ? null : configId.trim(); }
}
