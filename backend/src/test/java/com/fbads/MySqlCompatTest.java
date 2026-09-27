package com.fbads;

import com.fbads.engine.EngineClock;
import com.fbads.event.AppEvent;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EventStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool cũng phải chạy được trên MySQL 8 (nhiều máy cài sẵn MySQL thay vì MariaDB).
 * Dùng đúng driver MariaDB như khi chạy thật (DB_URL=jdbc:mariadb://...), chỉ đổi máy chủ DB sang MySQL 8.0.
 * Chạy hết migration Flyway, rồi ghi/đọc cài đặt, lịch, nhật ký (có cột JSON và TEXT) và thống kê (INSERT IGNORE, ON DUPLICATE KEY).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "fbads.engine.enabled=false")
class MySqlCompatTest {
    @Container
    static GenericContainer<?> mysql = new GenericContainer<>("mysql:8.0")
            .withEnv(Map.of("MYSQL_DATABASE", "fbads", "MYSQL_USER", "fbads", "MYSQL_PASSWORD", "fbads", "MYSQL_ROOT_PASSWORD", "root"))
            .withExposedPorts(3306)
            // MySQL khởi động 2 lần (lần đầu để tạo DB); chỉ lần sau mới nghe cổng 3306
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:mariadb://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/fbads?allowPublicKeyRetrieval=true");
        r.add("spring.datasource.username", () -> "fbads");
        r.add("spring.datasource.password", () -> "fbads");
    }

    @LocalServerPort
    int port;
    @Autowired
    EventStatsService stats;
    @Autowired
    EngineClock clock;

    @Test
    void runsOnMySql8() {
        Api api = new Api(port);
        assertThat(api.get("/api/state").status()).isEqualTo(200);
        assertThat(api.post("/api/settings", Map.of("ruleIntervalMin", 20)).status()).isEqualTo(200);
        assertThat(api.get("/api/state").body().get("settings").get("ruleIntervalMin").asInt()).isEqualTo(20);

        Api.Res saved = api.post("/api/schedules", Map.of("name", "Tắt đêm", "action", "off", "times", List.of("23:00"),
                "days", List.of(0, 1, 2, 3, 4, 5, 6), "targetMode", "list", "targets", List.of("mock_1"), "enabled", true));
        assertThat(saved.status()).as(String.valueOf(saved.body())).isEqualTo(200);
        assertThat(api.post("/api/schedules/" + saved.body().get("id").asString() + "/run", Map.of()).status()).isEqualTo(200);
        assertThat(api.get("/api/logs").body().get(0).get("kind").asString()).isEqualTo("schedule");

        // thống kê: lượt chạy lịch vừa rồi đã được đếm, và cùng một sự kiện nhận 2 lần chỉ đếm 1
        String today = clock.now().date();
        assertThat(actions(today, "schedule")).isGreaterThanOrEqualTo(1);
        AppEvent e = new AppEvent(UUID.randomUUID().toString(), AppEvent.LOG_CREATED, "logs", clock.millis(), false,
                Api.JSON.valueToTree(Map.of("kind", "mysql")));
        e.runInWorkspace(() -> stats.handle(e));
        e.runInWorkspace(() -> stats.handle(e));
        assertThat(actions(today, "mysql")).isEqualTo(1);

        // nhiều người dùng: tài khoản đầu tiên, workspace thứ hai, thành viên (bảng users/workspaces/workspace_members trên MySQL 8)
        assertThat(api.post("/api/setup", Map.of("username", "chu", "password", "MatKhau@2026")).status()).isEqualTo(200);
        assertThat(api.post("/api/members", Map.of("username", "nhanvien", "password", "NhanVien@2026", "role", "EDITOR")).status()).isEqualTo(200);
        assertThat(api.post("/api/workspaces", Map.of("name", "Khách B")).status()).isEqualTo(200);
        assertThat(api.get("/api/state").body().get("schedules").size()).isZero();
        assertThat(api.post("/api/settings", Map.of("ruleIntervalMin", 30)).status()).isEqualTo(200);
    }

    private int actions(String day, String source) {
        return WorkspaceContext.call(WorkspaceContext.DEFAULT, () -> stats.ofDay(day)).stream().filter(s -> s.getKey().source().equals(source)).mapToInt(s -> s.getActions()).sum();
    }
}
