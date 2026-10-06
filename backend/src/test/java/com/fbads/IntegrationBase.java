package com.fbads;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Nền chung cho test tích hợp chạy cả ứng dụng: MySQL + Redis thật (Testcontainers), dữ liệu giả, engine tắt.
 * Container khởi động một lần cho mọi lớp test con, và các lớp con dùng chung một Spring context (cùng cấu hình),
 * nên thêm lớp test không làm chậm thêm bước khởi động.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fbads.engine.enabled=false", "fbads.secret-key=khoa-test",
        "resilience4j.retry.instances.graphRead.wait-duration=1ms"})
abstract class IntegrationBase {
    @ServiceConnection
    static final MySQLContainer db = new MySQLContainer("mysql:8.0");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        db.start();
        redisServer.start();
    }
}
