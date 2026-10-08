package com.fbads.notify;

import com.fbads.event.AppEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Thông báo soạn từ sự kiện: phải giống hệt tin mà engine gửi trước khi chuyển sang sự kiện. */
class NotifierTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static AppEvent ev(String type, boolean telegram, Map<String, Object> data) {
        return new AppEvent("e1", type, "k", 0, telegram, JSON.valueToTree(data));
    }

    private static JsonNode log(Map<String, Object> m) { return JSON.valueToTree(m); }

    @Test
    void iconsAndTagFollowOutcome() {
        Map<String, Object> base = Map.of("source", "Lịch · Tắt đêm", "name", "Camp 1", "detail", "Đã tắt");
        assertThat(Notifier.logLine(log(base))).isEqualTo("✅ <b>Lịch · Tắt đêm</b>\nCamp 1: Đã tắt");
        assertThat(Notifier.logLine(log(with(base, "ok", false)))).startsWith("❌ <b>");
        assertThat(Notifier.logLine(log(with(base, "skipped", true)))).startsWith("⏭️ <b>");
        assertThat(Notifier.logLine(log(with(base, "dry", true)))).isEqualTo("🧪 <b>Lịch · Tắt đêm</b> (chạy thử)\nCamp 1: Đã tắt");
        // cảnh báo (rule "chỉ báo"): 🔔, không gắn "(chạy thử)" dù đang chạy thử; lỗi vẫn ưu tiên ❌
        Map<String, Object> notify = with(with(base, "dry", true), "action", Map.of("type", "notify"));
        assertThat(Notifier.logLine(log(notify))).isEqualTo("🔔 <b>Lịch · Tắt đêm</b>\nCamp 1: Đã tắt");
        assertThat(Notifier.logLine(log(with(notify, "ok", false)))).startsWith("❌ <b>");
        // trường trống in "null" như nối chuỗi Java trước đây
        assertThat(Notifier.logLine(log(Map.of("source", "Hệ thống")))).isEqualTo("✅ <b>Hệ thống</b>\nnull: null");
    }

    @Test
    void onlyFlaggedLogsAndReportsAreSent() {
        Map<String, Object> l = Map.of("source", "Rule", "name", "Camp", "detail", "x");
        assertThat(Notifier.noticeOf(ev(AppEvent.LOG_CREATED, true, l))).isEqualTo(new Notice(Notice.Topic.LOG, "✅ <b>Rule</b>\nCamp: x"));
        assertThat(Notifier.noticeOf(ev(AppEvent.LOG_CREATED, false, l))).isNull();
        assertThat(Notifier.noticeOf(ev(AppEvent.LOG_UPDATED, true, l))).isNull();
        assertThat(Notifier.noticeOf(ev(AppEvent.OBJECTS_CHANGED, true, Map.of("id", "1")))).isNull();
        assertThat(Notifier.noticeOf(ev(AppEvent.DAILY_REPORT, true, Map.of("text", "📊 Báo cáo"))))
                .isEqualTo(new Notice(Notice.Topic.REPORT, "📊 Báo cáo"));
    }

    /** Bản ghi cũ trong Kafka: cờ tên "telegram", loại "telegram.text" không có topic → vẫn gửi, coi là cảnh báo */
    @Test
    void readsEventsWrittenBeforeTheRename() {
        AppEvent old = JSON.readValue("{\"id\":\"e1\",\"type\":\"telegram.text\",\"key\":\"1:alerts\",\"at\":0,"
                + "\"telegram\":true,\"data\":{\"text\":\"⚠️ cũ\"}}", AppEvent.class);
        assertThat(old.shouldNotify()).isTrue();
        assertThat(Notifier.noticeOf(old)).isEqualTo(new Notice(Notice.Topic.ALERT, "⚠️ cũ"));
        assertThat(Notifier.noticeOf(ev(AppEvent.NOTICE, true, Map.of("topic", "COMPANY", "text", "📋 x"))))
                .isEqualTo(new Notice(Notice.Topic.COMPANY, "📋 x"));
    }

    @Test
    void noticeTitleAndPlainText() {
        Notice n = new Notice(Notice.Topic.ALERT, "⚠️ <b>Chi tiêu tăng vọt</b>\nCamp &lt;A&gt; &amp; B");
        assertThat(n.title()).isEqualTo("⚠️ Chi tiêu tăng vọt");
        assertThat(n.plainText()).isEqualTo("⚠️ Chi tiêu tăng vọt\nCamp <A> & B");
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        var copy = new java.util.LinkedHashMap<>(m);
        copy.put(k, v);
        return copy;
    }
}
