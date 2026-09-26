package com.fbads;

import com.fbads.engine.state.EngineState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Khởi động với IMPORT_FILE = data.json của bản Node → dữ liệu vào MariaDB, mật khẩu cũ vẫn đăng nhập được. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"fbads.engine.enabled=false", "fbads.import.file=src/test/resources/fixtures/data.json"})
class ImportIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer db = new MariaDBContainer("mariadb:11.8");

    @LocalServerPort
    int port;
    @Autowired
    EngineState state;

    @Test
    void importsNodeData() {
        Api api = new Api(port);
        assertThat(api.get("/api/state").status()).isEqualTo(401); // mật khẩu đã nhập theo
        assertThat(api.post("/api/login", Map.of("password", "MatKhau@2026")).status()).isEqualTo(200);

        JsonNode st = api.get("/api/state").body();
        assertThat(st.get("settings").get("adAccountIds").get(0).asString()).isEqualTo("123456789");
        assertThat(st.get("settings").get("ruleIntervalMin").asInt()).isEqualTo(20);
        assertThat(st.get("settings").get("passwordHash").asString()).isEmpty();
        assertThat(st.get("settings").get("accountTargets").get("123456789").get("cpa").asLong()).isEqualTo(150000);
        assertThat(st.get("schedules").size()).isEqualTo(2);
        assertThat(st.get("schedules").get(1).get("window").get("off").asString()).isEqualTo("22:00");
        assertThat(st.get("rules").get(0).get("conditions").get(1).get("vs").asString()).isEqualTo("target");

        JsonNode logs = api.get("/api/logs").body();
        assertThat(logs.get(0).get("id").asString()).isEqualTo("log00002"); // mới nhất trước, như bản Node
        assertThat(logs.get(1).get("ok").asBoolean()).isFalse();
        assertThat(logs.get(1).get("error").get("code").asInt()).isEqualTo(190);
        assertThat(state.resumes()).hasSize(1);
    }
}
