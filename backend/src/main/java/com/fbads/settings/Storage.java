package com.fbads.settings;

/** Nơi lưu dữ liệu (Cài đặt → Chung). Bản Java luôn lưu MySQL nên các trường còn lại cố định. */
public record Storage(String mode, String provider, Long lastSavedAt, String lastError, boolean pending) {
    public static final Storage MYSQL = new Storage("db", "MySQL", null, "", false);
}
