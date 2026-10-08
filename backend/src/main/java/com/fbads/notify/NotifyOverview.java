package com.fbads.notify;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** GET /api/notify: các loại kênh, các loại tin, và kênh đã cài của workspace */
public record NotifyOverview(List<NotifyType> types, List<NotifyTopic> topics, List<NotifyTargetView> channels) {
    /** Một loại kênh có trong code: giao diện vẽ form từ fields; defaultTopics = loại tin chọn sẵn khi thêm kênh */
    public record NotifyType(String type, String label, List<ConfigField> fields, String help, List<String> defaultTopics) {}

    /** Một loại tin (Notice.Topic) */
    public record NotifyTopic(String key, String label) {}

    /**
     * Một kênh đã cài. config đã bỏ các ô bí mật (để ""), savedSecrets = các ô bí mật đã có giá trị (hiện "đã lưu").
     */
    public record NotifyTargetView(String id, String type, String label, String name, boolean enabled, List<String> topics,
            ObjectNode config, List<String> savedSecrets) {}
}
