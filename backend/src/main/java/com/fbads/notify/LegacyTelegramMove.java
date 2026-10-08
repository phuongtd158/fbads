package com.fbads.notify;

import com.fbads.common.Ids;
import com.fbads.common.SecretConverter;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lúc khởi động: chuyển cài đặt Telegram kiểu cũ (cột telegram_token, telegram_chat_id của app_settings) thành một kênh
 * trong bảng notify_targets, nhận mọi loại tin như trước, rồi xoá ở chỗ cũ. Workspace nào chuyển rồi thì cột cũ trống, bỏ qua.
 * <p>
 * Làm bằng Java thay vì SQL vì token cũ đang được mã hoá bằng SECRET_KEY: phải giải mã ra rồi mã hoá lại cả chuỗi JSON.
 * Chạy sau DataImporter (dữ liệu nhập từ bản Node cũng có thể chứa Telegram) và trước SecretsAtRest.
 */
@Component
@Order(55)
public class LegacyTelegramMove implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyTelegramMove.class);

    private final JdbcTemplate jdbc;
    private final SecretConverter secrets;
    private final SettingsService settings;
    private final JsonMapper json;

    public LegacyTelegramMove(JdbcTemplate jdbc, SecretConverter secrets, SettingsService settings, JsonMapper json) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        int moved = 0;
        String sql = "SELECT id, telegram_token, telegram_chat_id FROM app_settings WHERE telegram_token <> '' OR telegram_chat_id <> ''";
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            String token = secrets.convertToEntityAttribute((String) row.get("telegram_token")); // giải mã nếu đã mã hoá
            String chatId = (String) row.get("telegram_chat_id");
            // đã có kênh Telegram (vd chuyển rồi mà bản cài đặt cũ trong bộ nhớ lỡ ghi lại token) thì không thêm kênh trùng
            boolean hasTelegram = jdbc.queryForObject("SELECT COUNT(*) FROM notify_targets WHERE workspace_id = ? AND type = ?",
                    Integer.class, row.get("id"), TelegramChannel.TYPE) > 0;
            if (token != null && !token.isEmpty() && !hasTelegram) {
                Map<String, String> cfg = new LinkedHashMap<>();
                cfg.put("token", token);
                cfg.put("chatId", chatId == null ? "" : chatId);
                jdbc.update("INSERT INTO notify_targets (id, workspace_id, type, name, enabled, topics, config) VALUES (?, ?, ?, ?, TRUE, ?, ?)",
                        Ids.uid(), row.get("id"), TelegramChannel.TYPE, "Telegram",
                        json.writeValueAsString(NotifyTargetService.ALL_TOPICS),
                        secrets.convertToDatabaseColumn(json.writeValueAsString(cfg)));
                moved++;
            }
            // chỉ có Chat ID mà không có token thì vốn không gửi được gì: bỏ luôn
            jdbc.update("UPDATE app_settings SET telegram_token = '', telegram_chat_id = '' WHERE id = ?", row.get("id"));
        }
        if (moved > 0) {
            settings.reload(); // bản cài đặt trong bộ nhớ còn giữ token cũ
            log.info("Đã chuyển cài đặt Telegram của {} workspace sang Kênh thông báo.", moved);
        }
    }
}
