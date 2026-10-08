package com.fbads;

import com.fbads.common.Ids;
import com.fbads.notify.Notice;
import com.fbads.notify.NotifyTarget;
import com.fbads.notify.NotifyTargetRepository;
import com.fbads.notify.channel.TelegramChannel;

import java.util.Arrays;
import java.util.Map;

/**
 * Cài nhanh kênh thông báo cho test (workspace hiện tại). Ghi thẳng vào bảng, không qua kiểm tra,
 * nên dùng được token/chat id ngắn như "123:abc", "42" với máy chủ Telegram giả.
 */
final class TestChannels {
    private TestChannels() {}

    /** Xoá mọi kênh rồi thêm một kênh Telegram nhận mọi loại tin */
    static NotifyTarget telegram(NotifyTargetRepository repo, String token, String chatId) {
        clear(repo);
        NotifyTarget t = new NotifyTarget(Ids.uid(), TelegramChannel.TYPE);
        t.setName("Telegram");
        t.setTopics(Arrays.stream(Notice.Topic.values()).map(Enum::name).toList());
        t.setConfig(Api.JSON.writeValueAsString(Map.of("token", token, "chatId", chatId)));
        return repo.save(t);
    }

    static void clear(NotifyTargetRepository repo) { repo.deleteAll(repo.findAll()); }
}
