package com.fbads;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fbads.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy cả ứng dụng trên MariaDB thật (Testcontainers), dữ liệu giả (mock), engine tắt.
 * Gọi API y như giao diện Vue gọi.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "fbads.engine.enabled=false")
class ApiIntegrationTest {
    @Container
    @ServiceConnection
    static MariaDBContainer db = new MariaDBContainer("mariadb:11.8");

    @LocalServerPort
    int port;
    @Autowired
    SettingsService settings;
    Api api;

    @BeforeEach
    void setUp() { api = new Api(port); }

    /** Luật kiểm tra lịch/rule cho kết quả giống hệt shared/validate.mjs (kỳ vọng do bản Node sinh ra) */
    @Test
    void validationMatchesNode() {
        JsonNode cases = NodeCompatTest.read("/fixtures/validate-parity.json");
        List<String> diffs = new ArrayList<>();
        for (JsonNode c : cases.values()) {
            String kind = c.get("kind").asString();
            String path = switch (kind) { case "schedule" -> "/api/schedules"; case "rule" -> "/api/rules"; default -> "/api/rules/preview"; };
            Api.Res r = api.post(path, c.get("input"));
            JsonNode exp = c.get("expected");
            boolean ok = r.status() == 200;
            ObjectNode got = Api.JSON.createObjectNode();
            got.put("ok", ok);
            got.set("errors", ok ? Api.JSON.createObjectNode() : r.body().get("errors"));
            got.set("warnings", ok ? r.body().get("warnings") : Api.JSON.createArrayNode());
            ObjectNode want = Api.JSON.createObjectNode();
            for (String k : List.of("ok", "errors", "warnings")) want.set(k, exp.get(k));
            if (!kind.equals("preview") && ok) {
                ObjectNode value = (ObjectNode) r.body().deepCopy();
                value.remove(List.of("id", "warnings"));
                got.set("value", normalize(value));
                want.set("value", normalize(exp.get("value")));
                api.delete(path + "/" + r.body().get("id").asString());
            }
            if (!got.equals(want)) diffs.add(kind + " " + c.get("input").path("name").asString() + "\n  node: " + want + "\n  java: " + got);
        }
        assertThat(diffs).isEmpty();
    }

    /** Bỏ khoá null (bản Java không ghi khoá rỗng) */
    private static JsonNode normalize(JsonNode n) {
        if (n == null) return null;
        if (n.isObject()) {
            ObjectNode o = Api.JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> e : n.properties()) if (!e.getValue().isNull()) o.set(e.getKey(), normalize(e.getValue()));
            return o;
        }
        if (n.isArray()) {
            var a = Api.JSON.createArrayNode();
            for (JsonNode x : n.values()) a.add(normalize(x));
            return a;
        }
        if (n.isNumber() && n.asDouble() == Math.rint(n.asDouble())) return Api.JSON.getNodeFactory().numberNode(n.asLong());
        return n;
    }

    @Test
    void scheduleLifecycleAndManualActions() {
        Api.Res saved = api.post("/api/schedules", Map.of("name", "Tắt đêm test", "action", "off", "times", List.of("23:00"),
                "days", List.of(0, 1, 2, 3, 4, 5, 6), "targetMode", "list", "targets", List.of("mock_1"), "enabled", true));
        assertThat(saved.status()).as(String.valueOf(saved.body())).isEqualTo(200);
        String id = saved.body().get("id").asString();
        assertThat(api.get("/api/state").body().get("schedules").values()).anyMatch(s -> s.get("id").asString().equals(id));
        assertThat(api.get("/api/state").body().get("storage").get("mode").asString()).isEqualTo("db");

        assertThat(api.post("/api/schedules/" + id + "/run", Map.of()).status()).isEqualTo(200);
        JsonNode last = api.get("/api/logs").body().get(0);
        assertThat(last.get("kind").asString()).isEqualTo("schedule");
        assertThat(last.get("ok").asBoolean()).isTrue();
        assertThat(last.get("mode").asString()).isEqualTo("mock"); // mặc định dùng dữ liệu giả

        assertThat(api.post("/api/objects/mock_1/budget", Map.of("amount", -5)).body().get("errors").has("amount")).isTrue();
        assertThat(api.post("/api/objects/mock_1/budget", Map.of("amount", 654321, "name", "Camp 1")).status()).isEqualTo(200);
        JsonNode manual = api.get("/api/logs").body().get(0);
        assertThat(manual.get("after").get("dailyBudget").asLong()).isEqualTo(654321);
        long before = manual.get("before").get("dailyBudget").asLong();

        Api.Res undo = api.post("/api/logs/" + manual.get("id").asString() + "/undo", Map.of());
        assertThat(undo.status()).isEqualTo(200);
        assertThat(api.post("/api/logs/" + manual.get("id").asString() + "/undo", Map.of()).status()).isEqualTo(400);
        JsonNode obj = api.get("/api/objects").body().get("items").values().stream().filter(o -> o.get("id").asString().equals("mock_1")).findFirst().orElseThrow();
        assertThat(obj.get("dailyBudget").asLong()).isEqualTo(before);

        assertThat(api.delete("/api/schedules/" + id).status()).isEqualTo(200);
        assertThat(api.post("/api/schedules/" + id + "/run", Map.of()).status()).isEqualTo(404);
        assertThat(api.get("/api/khong-co").status()).isEqualTo(404);
    }

    @Test
    void passwordLoginAndGuards() {
        assertThat(api.post("/api/password", Map.of("newPassword", "12345678")).status()).isEqualTo(400);
        assertThat(api.post("/api/password", Map.of("newPassword", "MatKhau@2026")).status()).isEqualTo(200);
        try {
            Api stranger = new Api(port);
            assertThat(stranger.get("/api/state").status()).isEqualTo(401);
            assertThat(stranger.get("/api/auth").body().get("required").asBoolean()).isTrue();
            assertThat(stranger.post("/api/login", Map.of("password", "sai")).status()).isEqualTo(401);
            assertThat(stranger.post("/api/login", Map.of("password", "MatKhau@2026")).status()).isEqualTo(200);
            assertThat(stranger.get("/api/state").status()).isEqualTo(200);
            assertThat(stranger.post("/api/logout", Map.of()).status()).isEqualTo(200);
            assertThat(stranger.get("/api/state").status()).isEqualTo(401);
            // phiên cũ vẫn dùng được sau khi đổi mật khẩu (đã đăng nhập lại tự động)
            assertThat(api.get("/api/state").status()).isEqualTo(200);
        } finally {
            settings.update(s -> s.setPasswordHash("")); // các test khác chạy không cần đăng nhập
        }
    }
}
