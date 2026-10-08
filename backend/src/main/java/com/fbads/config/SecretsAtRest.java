package com.fbads.config;

import com.fbads.common.SecretConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lúc khởi động: có SECRET_KEY thì mã hoá luôn các token còn lưu nguyên văn (dữ liệu từ trước khi đặt khoá);
 * chưa có SECRET_KEY mà DB đang chứa token thì cảnh báo.
 * Các cột bí mật: token Facebook, App Secret (app_settings) và cấu hình kênh thông báo (notify_targets.config).
 */
@Component
@Order(60) // sau khi nhập dữ liệu cũ (DataImporter) và chuyển Telegram cũ (LegacyTelegramMove)
public class SecretsAtRest implements ApplicationRunner {
    /** Bảng → các cột bí mật của bảng đó */
    static final Map<String, List<String>> COLUMNS = Map.of(
            "app_settings", List.of("access_token", "fb_app_secret"),
            "notify_targets", List.of("config"));
    private static final Logger log = LoggerFactory.getLogger(SecretsAtRest.class);

    private final JdbcTemplate jdbc;
    private final SecretConverter secrets;

    public SecretsAtRest(JdbcTemplate jdbc, SecretConverter secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        int plain = 0;
        for (Map.Entry<String, List<String>> table : COLUMNS.entrySet()) {
            String name = table.getKey();
            List<String> cols = table.getValue();
            for (Map<String, Object> row : jdbc.queryForList("SELECT id, " + String.join(", ", cols) + " FROM " + name)) {
                for (String col : cols) {
                    String v = (String) row.get(col);
                    if (v == null || v.isEmpty() || SecretConverter.isEncrypted(v)) continue;
                    if (!secrets.enabled()) { plain++; continue; }
                    jdbc.update("UPDATE " + name + " SET " + col + " = ? WHERE id = ?", secrets.convertToDatabaseColumn(v), row.get("id"));
                    plain++;
                }
            }
        }
        if (plain == 0) return;
        if (secrets.enabled()) log.info("Đã mã hoá {} token đang lưu nguyên văn trong DB.", plain);
        else log.warn("DB đang lưu {} token Facebook / kênh thông báo nguyên văn. Đặt biến môi trường SECRET_KEY (chuỗi ngẫu "
                + "nhiên dài, giữ bí mật) để mã hoá.", plain);
    }
}
