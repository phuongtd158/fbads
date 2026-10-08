package com.fbads.notify;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;

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
