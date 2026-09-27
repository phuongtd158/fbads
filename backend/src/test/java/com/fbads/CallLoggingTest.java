package com.fbads;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bật log chi tiết ở mọi tầng + SQL (như profile dev): một lần đổi ngân sách phải thấy đủ đường đi
 * controller → service → repository → SQL, và không lộ mật khẩu. Tắt một tầng thì tầng đó im lặng.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fbads.engine.enabled=false", "fbads.app-password=MatKhau@2026",
        "fbads.logging.controller=true", "fbads.logging.service=true", "fbads.logging.repository=true",
        "fbads.logging.engine=false", "fbads.logging.sql=true"})
class CallLoggingTest {
    @Container
    @ServiceConnection
    static MariaDBContainer db = new MariaDBContainer("mariadb:11.8");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @LocalServerPort
    int port;

    @Test
    void everyLayerIsLogged(CapturedOutput out) {
        Api api = new Api(port);
        assertThat(api.post("/api/login", Map.of("password", "MatKhau@2026")).status()).isEqualTo(200);
        assertThat(api.post("/api/objects/mock_1/budget", Map.of("amount", 515000, "name", "Camp 1")).status()).isEqualTo(200);

        assertThat(out.getOut())
                .contains("fbads.calls.controller", "→ ObjectsController.budget(id=\"mock_1\"")
                .contains("→ ObjectService.setBudget(id=\"mock_1\", amount=515000.0, name=\"Camp 1\")")
                .containsPattern("← ObjectService\\.setBudget \\d+ ms")
                .contains("→ LogRepository.save(entity=")
                .contains("org.hibernate.SQL", "insert", "binding parameter")
                .contains("AuthService.authenticate(username=\"admin\", password=***)")
                .doesNotContain("MatKhau@2026")
                .doesNotContain("fbads.calls.engine");
    }
}
