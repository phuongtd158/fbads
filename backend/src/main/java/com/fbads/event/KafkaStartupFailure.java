package com.fbads.event;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

/**
 * KAFKA_ENABLED=true mà Kafka chưa chạy: lúc khởi động KafkaAdmin không tạo được topic và báo
 * "Could not configure topics" kèm một đống lỗi tiếng Anh. Lớp này đổi nó thành thông báo tiếng Việt
 * nói rõ nguyên nhân và cách sửa. Tool vẫn dừng như cũ (spring.kafka.admin.fail-fast).
 * Đăng ký trong META-INF/spring.factories, Spring Boot tự gọi khi khởi động lỗi.
 */
public class KafkaStartupFailure extends AbstractFailureAnalyzer<IllegalStateException> {
    private final Environment env;

    public KafkaStartupFailure(Environment env) {
        this.env = env;
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IllegalStateException cause) {
        if (!"Could not configure topics".equals(cause.getMessage())) return null;   // lỗi khác: để Spring Boot báo như thường

        String servers = env == null ? "localhost:9092" : env.getProperty("spring.kafka.bootstrap-servers", "localhost:9092");
        String description = "Đang bật Kafka (KAFKA_ENABLED=true) nhưng không kết nối được Kafka ở " + servers + ".";
        String action = String.join(System.lineSeparator(),
                "Chọn một trong hai cách:",
                "  1. Không cần Kafka: xoá KAFKA_ENABLED hoặc đặt KAFKA_ENABLED=false (IntelliJ: Run > Edit Configurations > Environment variables).",
                "  2. Muốn dùng Kafka: bật Kafka trước rồi chạy lại tool:",
                "       docker run -d --name fbads-kafka -p 127.0.0.1:9092:9092 apache/kafka:4.2.0",
                "     (đã tạo container rồi thì: docker start fbads-kafka)",
                "     Kafka bật bằng docker compose nghe ở cổng 9094: đặt thêm KAFKA_BOOTSTRAP_SERVERS=localhost:9094.");
        return new FailureAnalysis(description, action, cause);
    }
}
