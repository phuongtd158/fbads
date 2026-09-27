package com.fbads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Facebook Ads Auto Tool: lịch bật/tắt/ngân sách, rule theo hiệu quả, dừng khẩn, báo cáo Telegram.
 * Giao diện Vue (thư mục public/) giữ nguyên, API giữ đúng URL và dữ liệu như bản Node.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FbadsApplication {

    public static void main(String[] args) {
        // Mặc định chỉ nghe trên máy này (127.0.0.1). Trên Render (bắt buộc nghe 0.0.0.0) tự mở ra; nơi khác đặt HOST=0.0.0.0.
        String host = System.getenv("HOST");
        if (host == null || host.isBlank()) host = System.getenv("RENDER") != null ? "0.0.0.0" : "127.0.0.1";
        System.setProperty("server.address", host);
        SpringApplication.run(FbadsApplication.class, args);
    }
}
