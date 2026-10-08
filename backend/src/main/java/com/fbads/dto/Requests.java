package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fbads.common.JsNumber;
import com.fbads.common.Json;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Body của các request đơn giản. Jackson đọc JSON thẳng vào record; gửi sai kiểu (vd. "on": "abc")
 * thì ApiExceptionHandler trả 400 { error, errors: { trường: "Sai kiểu dữ liệu" } }.
 * Trường chữ không gửi = "" (constructor gọn bên dưới), như `req.body.x || ''` của bản Node.
 * Body bỏ trống: controller dùng hằng EMPTY của từng record, giống gửi {}.
 * Luật kiểm tra nằm ở chú thích Bean Validation (controller dùng @Valid) hoặc ở service.
 */
public final class Requests {
    private Requests() {}

    /** Token Facebook (bỏ trống = token đã lưu) + App ID/Secret khi gia hạn token */
    public record FbToken(String token, String appId, String appSecret) {
        public static final FbToken EMPTY = new FbToken(null, null, null);

        public FbToken { token = Json.trimmed(token); appId = Json.trimmed(appId); appSecret = Json.trimmed(appSecret); }
    }

    /**
     * Bắt đầu đăng nhập bằng Facebook. appId/appSecret bỏ trống = dùng cái đã lưu.
     * configId: null = không gửi (dùng cái đã lưu), "" = bỏ Configuration ID.
     */
    public record OauthStart(String appId, String appSecret, String configId) {
        public static final OauthStart EMPTY = new OauthStart(null, null, null);

        public OauthStart { appId = Json.trimmed(appId); appSecret = Json.trimmed(appSecret); configId = configId == null ? null : configId.trim(); }
    }

    /** POST /api/company/reports/build: tạo bản báo cáo của một mốc ngay; notify = nhắn Telegram như đến mốc */
    public record CompanyBuild(@JsNumber Double slot,
            @JsonProperty("notify") Boolean telegram) {
        public static final CompanyBuild EMPTY = new CompanyBuild(null, null);
    }

    /** POST /api/company/reports/{id}/update: cập nhật báo cáo đã có trên công ty, lý do bắt buộc */
    public record CompanyUpdate(String reason) {
        public static final CompanyUpdate EMPTY = new CompanyUpdate(null);
    }

    /**
     * POST /api/notify/channels (thêm) và /api/notify/channels/{id} (sửa): một kênh thông báo.
     * config để dạng JSON vì mỗi loại kênh có các ô khác nhau (Telegram: token + chatId; Gmail: tài khoản + mật khẩu…);
     * lớp kênh (NotifyChannel.validate) kiểm tra từng ô.
     *
     * @param type   loại kênh ("telegram"…); chỉ dùng khi thêm, sửa thì giữ loại cũ
     * @param topics tên các Notice.Topic kênh nhận; null = mọi loại tin
     */
    public record NotifyTargetSave(String type, String name, Boolean enabled, List<String> topics, ObjectNode config) {
        public static final NotifyTargetSave EMPTY = new NotifyTargetSave(null, null, null, null, null);
    }
}
