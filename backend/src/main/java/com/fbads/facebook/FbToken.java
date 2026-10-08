package com.fbads.facebook;

import com.fbads.common.Json;

/** Token Facebook (bỏ trống = token đã lưu) + App ID/Secret khi gia hạn token */
public record FbToken(String token, String appId, String appSecret) {
    public static final FbToken EMPTY = new FbToken(null, null, null);

    public FbToken { token = Json.trimmed(token); appId = Json.trimmed(appId); appSecret = Json.trimmed(appSecret); }
}
