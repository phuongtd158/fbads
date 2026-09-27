package com.fbads;

import com.fbads.config.CacheConfig;
import com.fbads.dto.FbSnapshots;
import com.fbads.engine.ActionExecutor;
import com.fbads.engine.EngineClock;
import com.fbads.engine.EngineLock;
import com.fbads.service.EventStatsService;
import com.fbads.service.LogService;
import com.fbads.service.ReportService;
import com.fbads.service.SettingsService;
import com.fbads.service.TelegramService;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @LocalServerPort
    int port;
    @Autowired
    SettingsService settings;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    CacheManager caches;
    @Autowired
    EngineLock engineLock;
    @Autowired
    LockProvider locks;
    @Autowired
    ActionExecutor executor;
    @Autowired
    LogService logs;
    @Autowired
    TelegramService telegram;
    @Autowired
    EventStatsService stats;
    @Autowired
    EngineClock clock;
    @Autowired
    ReportService reports;
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

    /** Số camp tải từ Facebook được lưu ở Redis; đổi ngân sách thì bản ở Redis bị xoá */
    @Test
    void objectsAreCachedInRedis() {
        assertThat(api.get("/api/objects?refresh=1").status()).isEqualTo(200);
        var objects = caches.getCache(CacheConfig.OBJECTS);
        FbSnapshots.Objects saved = objects.get("mock", FbSnapshots.Objects.class);
        assertThat(saved).isNotNull();
        assertThat(saved.items()).anyMatch(o -> o.id.equals("mock_1"));
        assertThat(redis.keys("fbads:cache:fb-objects::*")).isNotEmpty();

        assertThat(api.post("/api/objects/mock_2/budget", Map.of("amount", 222000, "name", "Camp 2")).status()).isEqualTo(200);
        assertThat(objects.get("mock")).isNull();
    }

    /** Vòng tự động (@SchedulerLock) và nút "Chạy ngay" (EngineLock) dùng chung một khoá trên Redis */
    @Test
    void engineLockIsSharedThroughRedis() {
        LockConfiguration other = new LockConfiguration(Instant.now(), EngineLock.NAME, Duration.ofMinutes(1), Duration.ZERO);
        boolean blocked = engineLock.run(() -> locks.lock(other).isEmpty());
        assertThat(blocked).isTrue();
        var after = locks.lock(other); // chạy xong thì khoá được nhả
        assertThat(after).isPresent();
        after.get().unlock();
    }

    /** Phiên đăng nhập nằm ở Redis; nhập sai 5 lần thì khoá 15 phút (đếm ở Redis) */
    @Test
    void sessionsAndLoginLockoutInRedis() {
        assertThat(api.post("/api/password", Map.of("newPassword", "MatKhau@2026")).status()).isEqualTo(200);
        try {
            Api stranger = new Api(port);
            assertThat(stranger.post("/api/login", Map.of("password", "MatKhau@2026")).status()).isEqualTo(200);
            assertThat(redis.keys("fbads:session:sessions:*")).isNotEmpty();

            redis.delete(redis.keys("fbads:login:*")); // test khác có thể đã nhập sai
            Api attacker = new Api(port);
            for (int i = 0; i < 5; i++) assertThat(attacker.post("/api/login", Map.of("password", "sai")).status()).isEqualTo(401);
            Api.Res locked = attacker.post("/api/login", Map.of("password", "MatKhau@2026"));
            assertThat(locked.status()).isEqualTo(429);
            assertThat(locked.body().get("error").asString()).contains("15 phút");
            String lockKey = redis.keys("fbads:login:lock:*").iterator().next();
            assertThat(redis.getExpire(lockKey)).isBetween(1L, 900L);
        } finally {
            redis.delete(redis.keys("fbads:login:*"));
            settings.update(s -> s.setPasswordHash(""));
        }
    }

    /** Đổi ngân sách → trình duyệt đang nghe WebSocket nhận ngay dòng nhật ký mới và sự kiện camp đổi */
    @Test
    void liveEventsOverWebSocket() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession ws = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        try {
            BlockingQueue<String> logs = new LinkedBlockingQueue<>(), objects = new LinkedBlockingQueue<>();
            ws.subscribe("/topic/logs", collect(logs));
            ws.subscribe("/topic/objects", collect(objects));
            Thread.sleep(300); // chờ SUBSCRIBE tới server

            assertThat(api.post("/api/objects/mock_3/budget", Map.of("amount", 333000, "name", "Camp 3")).status()).isEqualTo(200);
            JsonNode obj = Api.JSON.readTree(objects.poll(10, TimeUnit.SECONDS));
            assertThat(obj.get("id").asString()).isEqualTo("mock_3");
            JsonNode log = Api.JSON.readTree(logs.poll(10, TimeUnit.SECONDS));
            assertThat(log.get("after").get("dailyBudget").asLong()).isEqualTo(333000);
            assertThat(log.get("id").asString()).isEqualTo(api.get("/api/logs").body().get(0).get("id").asString());
        } finally {
            ws.disconnect();
        }

        // Đã đặt mật khẩu mà chưa đăng nhập thì không mở được WebSocket
        assertThat(api.post("/api/password", Map.of("newPassword", "MatKhau@2026")).status()).isEqualTo(200);
        try {
            var stranger = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {});
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stranger.get(10, TimeUnit.SECONDS)).hasMessageContaining("401");
        } finally {
            settings.update(s -> s.setPasswordHash(""));
        }
    }

    private static StompFrameHandler collect(BlockingQueue<String> into) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return String.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) { into.add((String) payload); }
        };
    }

    /**
     * Không bật Kafka (mặc định): sự kiện đi bằng Spring events, các consumer chạy ngay trên luồng ghi nhật ký.
     * Lịch/rule ghi nhật ký → Telegram nhận tin (giống hệt trước đây) và thống kê cộng 1; thao tác tay thì không báo Telegram.
     */
    @Test
    void eventsWithoutKafka() throws Exception {
        try (TelegramStub tg = new TelegramStub()) {
            telegram.setApiBase(tg.base());
            settings.update(s -> { s.setTelegramToken("123:abc"); s.setTelegramChatId("111"); });
            String today = clock.now().date();
            int before = actions(today, "schedule");
            int manualBefore = actions(today, "manual");

            executor.record(false, e -> { e.setKind("schedule"); e.setSource("Lịch · Tắt đêm"); e.setName("Camp 1"); e.setDetail("Đã tắt"); });
            assertThat(tg.texts).containsExactly("✅ <b>Lịch · Tắt đêm</b>\nCamp 1: Đã tắt");

            executor.record(true, e -> { e.setKind("schedule"); e.setSource("Lịch · Tắt đêm"); e.setName("-"); e.setDetail("Không có gì để làm"); });
            logs.log(e -> { e.setKind("manual"); e.setSource("Thủ công"); e.setName("Camp 1"); e.setDetail("Tắt"); });
            assertThat(tg.texts).hasSize(1); // im lặng và thao tác tay: không báo
            assertThat(actions(today, "schedule")).isEqualTo(before + 2);
            assertThat(actions(today, "manual")).isEqualTo(manualBefore + 1);

            tg.status = 500; // Telegram lỗi: không có Kafka để thử lại, chỉ ghi log, việc ghi nhật ký vẫn xong
            assertThat(executor.record(false, e -> { e.setKind("rule"); e.setSource("Rule"); e.setName("Camp 2"); e.setDetail("Đã tắt"); }).getId()).isNotNull();
            assertThat(tg.texts).hasSize(2);

            // báo cáo hằng ngày: tới giờ thì gửi, lượt sau cùng ngày không gửi lại
            tg.status = 200;
            settings.update(s -> s.setReportTime("08:00"));
            clock.setClock(Clock.fixed(Instant.parse("2031-03-04T01:02:00Z"), ZoneOffset.UTC)); // 08:02 giờ Việt Nam
            reports.tick();
            reports.tick();
            assertThat(tg.texts).hasSize(3);
            assertThat(tg.texts.get(2)).startsWith("📊 <b>Báo cáo Facebook Ads</b>");
        } finally {
            clock.setClock(Clock.systemUTC());
            settings.update(s -> { s.setTelegramToken(""); s.setTelegramChatId(""); });
            telegram.setApiBase("https://api.telegram.org");
        }
    }

    private int actions(String day, String source) {
        return stats.ofDay(day).stream().filter(s -> s.getKey().source().equals(source)).mapToInt(s -> s.getActions()).sum();
    }
}
