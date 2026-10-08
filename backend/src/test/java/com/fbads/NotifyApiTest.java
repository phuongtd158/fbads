package com.fbads;

import com.fbads.common.SecretConverter;
import com.fbads.engine.ActionExecutor;
import com.fbads.entity.LogKind;
import com.fbads.notify.LegacyTelegramMove;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.repository.NotifyTargetRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kênh thông báo: API thêm/sửa/xoá/gửi thử, lọc theo loại tin, nhiều kênh cùng lúc, chuyển Telegram kiểu cũ sang bảng mới. */
class NotifyApiTest extends IntegrationBase {
    static final String TOKEN = "123456789:AAbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @LocalServerPort int port;
    @Autowired NotifyTargetRepository notifyTargets;
    @Autowired TelegramChannel telegram;
    @Autowired ActionExecutor executor;
    @Autowired LegacyTelegramMove legacyMove;
    @Autowired SettingsService settings;
    @Autowired SecretConverter secrets;
    @Autowired JdbcTemplate jdbc;
    Api api;
    TelegramStub tg;
    WorkspaceContext.Scope ws;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(port);
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        tg = new TelegramStub();
        telegram.setApiBase(tg.base());
        TestChannels.clear(notifyTargets);
    }

    @AfterEach
    void tearDown() {
        TestChannels.clear(notifyTargets);
        telegram.setApiBase("https://api.telegram.org");
        tg.close();
        ws.close();
    }

    private Map<String, Object> body(String name, List<String> topics, String token, String chatId) {
        return Map.of("type", "telegram", "name", name, "topics", topics, "config", Map.of("token", token, "chatId", chatId));
    }

    @Test
    void createValidateMaskAndKeepSecret() {
        JsonNode overview = api.get("/api/notify").body();
        assertThat(overview.get("types").get(0).get("type").asString()).isEqualTo("telegram");
        assertThat(overview.get("types").get(0).get("fields").get(0).get("secret").asBoolean()).isTrue();
        assertThat(overview.get("topics").values()).extracting(t -> t.get("key").asString())
                .containsExactly("LOG", "ALERT", "REPORT", "COMPANY");
        // Gmail: mặc định không nhận Nhật ký tự động (giới hạn khoảng 500 thư/ngày)
        assertThat(overview.get("types").get(1).get("type").asString()).isEqualTo("email");
        assertThat(overview.get("types").get(1).get("defaultTopics").values()).extracting(JsonNode::asString)
                .containsExactly("ALERT", "REPORT", "COMPANY");

        // sai: từng ô báo lỗi riêng
        Api.Res bad = api.post("/api/notify/channels", body("Nhóm A", List.of(), "abc", "x"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("errors").has("config.token")).isTrue();
        assertThat(bad.body().get("errors").has("config.chatId")).isTrue();
        assertThat(bad.body().get("errors").has("topics")).isTrue();
        assertThat(api.post("/api/notify/channels", Map.of("type", "fax")).body().get("errors").has("type")).isTrue();

        // đúng: Chat ID được chuẩn hoá, token không bao giờ trả về
        Api.Res ok = api.post("/api/notify/channels", body("Nhóm A", List.of("ALERT", "LOG"), TOKEN, "111111; 111111 , @kenh_cua_toi"));
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(200);
        String id = ok.body().get("id").asString();
        assertThat(ok.body().get("config").get("token").asString()).isEmpty();
        assertThat(ok.body().get("config").get("chatId").asString()).isEqualTo("111111, @kenh_cua_toi");
        assertThat(ok.body().get("savedSecrets").values()).extracting(JsonNode::asString).containsExactly("token");
        assertThat(ok.body().get("topics").values()).extracting(JsonNode::asString).containsExactly("LOG", "ALERT");
        assertThat(api.get("/api/notify").body().toString()).doesNotContain(TOKEN);

        // sửa mà để trống token = giữ token cũ
        Api.Res edited = api.post("/api/notify/channels/" + id, Map.of("name", "Nhóm B", "config", Map.of("token", "", "chatId", "222222")));
        assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
        assertThat(notifyTargets.findById(id).orElseThrow().getConfig()).contains(TOKEN).contains("222222");

        // gửi thử: tới đúng người nhận, kết quả ghi rõ kênh
        Api.Res test = api.post("/api/notify/channels/" + id + "/test", Map.of());
        assertThat(test.status()).isEqualTo(200);
        assertThat(test.body().get("results").get(0).get("id").asString()).isEqualTo("Nhóm B · 222222");
        assertThat(tg.texts).hasSize(1);

        assertThat(api.delete("/api/notify/channels/" + id).status()).isEqualTo(200);
        assertThat(api.post("/api/notify/channels/" + id + "/test", Map.of()).status()).isEqualTo(404);
    }

    /** Kênh chỉ nhận Cảnh báo thì không nhận Nhật ký tự động; kênh tắt thì không nhận gì */
    @Test
    void topicsAndEnabledDecideWhoGetsWhat() {
        {
            api.post("/api/notify/channels", body("Nhật ký", List.of("LOG"), TOKEN, "111111")).body().get("id").asString();
            api.post("/api/notify/channels", body("Chỉ cảnh báo", List.of("ALERT"), TOKEN, "222222"));
            String off = api.post("/api/notify/channels", body("Đang tắt", List.of("LOG"), TOKEN, "333333")).body().get("id").asString();
            api.post("/api/notify/channels/" + off, Map.of("enabled", false));

            executor.record(false, e -> { e.setKind(LogKind.RULE); e.setSource("Rule"); e.setName("Camp 1"); e.setDetail("Đã tắt"); });
            assertThat(tg.texts).containsExactly("✅ <b>Rule</b>\nCamp 1: Đã tắt");

            // kênh lỗi không chặn kênh khác (không Kafka: chỉ ghi log)
            api.post("/api/notify/channels/" + off, Map.of("enabled", true));
            tg.status = 500;
            executor.record(false, e -> { e.setKind(LogKind.RULE); e.setSource("Rule"); e.setName("Camp 2"); e.setDetail("Đã tắt"); });
            assertThat(tg.count("✅ <b>Rule</b>\nCamp 2: Đã tắt")).isEqualTo(2); // cả 2 kênh LOG đều được thử
        }
    }

    /** Telegram kiểu cũ trong app_settings (token đã mã hoá) → một kênh nhận mọi loại tin, cột cũ được xoá */
    @Test
    void legacyTelegramSettingsAreMoved() {
        jdbc.update("UPDATE app_settings SET telegram_token = ?, telegram_chat_id = ? WHERE id = 1",
                secrets.convertToDatabaseColumn("123:cu"), "42");
        legacyMove.run(null);
        legacyMove.run(null); // chạy lại: không thêm kênh trùng

        assertThat(notifyTargets.findAll()).hasSize(1);
        var moved = notifyTargets.findAll().getFirst();
        assertThat(moved.getType()).isEqualTo("telegram");
        assertThat(moved.getTopics()).containsExactly("LOG", "ALERT", "REPORT", "COMPANY");
        assertThat(moved.getConfig()).contains("123:cu").contains("42");
        assertThat(jdbc.queryForObject("SELECT config FROM notify_targets WHERE id = ?", String.class, moved.getId())).startsWith("enc:v1:");
        assertThat(jdbc.queryForMap("SELECT telegram_token, telegram_chat_id FROM app_settings WHERE id = 1"))
                .containsEntry("telegram_token", "").containsEntry("telegram_chat_id", "");
        assertThat(settings.get().getTelegramToken()).isEmpty();
    }
}
