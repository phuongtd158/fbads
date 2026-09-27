package com.fbads.service;

import com.fbads.event.AppEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Nội dung tin Telegram soạn từ sự kiện: phải giống hệt tin mà engine gửi trước khi chuyển sang sự kiện. */
class TelegramNotifierTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static AppEvent ev(String type, boolean telegram, Map<String, Object> data) {
        return new AppEvent("e1", type, "k", 0, telegram, JSON.valueToTree(data));
    }

    private static JsonNode log(Map<String, Object> m) { return JSON.valueToTree(m); }

    @Test
    void iconsAndTagFollowOutcome() {
        Map<String, Object> base = Map.of("source", "Lịch · Tắt đêm", "name", "Camp 1", "detail", "Đã tắt");
        assertThat(TelegramNotifier.logLine(log(base))).isEqualTo("✅ <b>Lịch · Tắt đêm</b>\nCamp 1: Đã tắt");
        assertThat(TelegramNotifier.logLine(log(with(base, "ok", false)))).startsWith("❌ <b>");
        assertThat(TelegramNotifier.logLine(log(with(base, "skipped", true)))).startsWith("⏭️ <b>");
        assertThat(TelegramNotifier.logLine(log(with(base, "dry", true)))).isEqualTo("🧪 <b>Lịch · Tắt đêm</b> (chạy thử)\nCamp 1: Đã tắt");
        // cảnh báo (rule "chỉ báo"): 🔔, không gắn "(chạy thử)" dù đang chạy thử; lỗi vẫn ưu tiên ❌
        Map<String, Object> notify = with(with(base, "dry", true), "action", Map.of("type", "notify"));
        assertThat(TelegramNotifier.logLine(log(notify))).isEqualTo("🔔 <b>Lịch · Tắt đêm</b>\nCamp 1: Đã tắt");
        assertThat(TelegramNotifier.logLine(log(with(notify, "ok", false)))).startsWith("❌ <b>");
        // trường trống in "null" như nối chuỗi Java trước đây
        assertThat(TelegramNotifier.logLine(log(Map.of("source", "Hệ thống")))).isEqualTo("✅ <b>Hệ thống</b>\nnull: null");
    }

    @Test
    void onlyFlaggedLogsAndReportsAreSent() {
        Map<String, Object> l = Map.of("source", "Rule", "name", "Camp", "detail", "x");
        assertThat(TelegramNotifier.textOf(ev(AppEvent.LOG_CREATED, true, l))).startsWith("✅");
        assertThat(TelegramNotifier.textOf(ev(AppEvent.LOG_CREATED, false, l))).isNull();
        assertThat(TelegramNotifier.textOf(ev(AppEvent.LOG_UPDATED, true, l))).isNull();
        assertThat(TelegramNotifier.textOf(ev(AppEvent.OBJECTS_CHANGED, true, Map.of("id", "1")))).isNull();
        assertThat(TelegramNotifier.textOf(ev(AppEvent.DAILY_REPORT, true, Map.of("text", "📊 Báo cáo")))).isEqualTo("📊 Báo cáo");
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        var copy = new java.util.LinkedHashMap<>(m);
        copy.put(k, v);
        return copy;
    }
}
