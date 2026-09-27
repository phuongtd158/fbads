package com.fbads;

import com.fbads.engine.ActionExecutor;
import com.fbads.engine.EngineClock;
import com.fbads.event.AppEvent;
import com.fbads.event.EventBus;
import com.fbads.event.EventTopics;
import com.fbads.service.EventStatsService;
import com.fbads.service.SettingsService;
import com.fbads.service.TelegramService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Bật Kafka thật (Testcontainers): một sự kiện được cả 3 consumer nhận (Telegram, thống kê, WebSocket),
 * gửi Telegram lỗi thì thử lại qua topic riêng rồi vào DLT, sự kiện nhận trùng chỉ đếm một lần.
 * Thời gian chờ giữa các lần thử rút xuống 300 ms cho test nhanh.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fbads.engine.enabled=false", "fbads.kafka.enabled=true",
        "fbads.kafka.telegram.attempts=3", "fbads.kafka.telegram.backoff-ms=300"})
class KafkaEventsTest {
    @Container
    @ServiceConnection
    static MariaDBContainer db = new MariaDBContainer("mariadb:11.8");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.0");

    static TelegramStub tg;

    @LocalServerPort
    int port;
    @Autowired
    KafkaListenerEndpointRegistry listeners;
    @Autowired
    ActionExecutor executor;
    @Autowired
    EventBus events;
    @Autowired
    TelegramService telegram;
    @Autowired
    SettingsService settings;
    @Autowired
    EventStatsService stats;
    @Autowired
    EngineClock clock;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JsonMapper json;

    @BeforeAll
    static void startStub() throws Exception { tg = new TelegramStub(); }

    @AfterAll
    static void stopStub() { tg.close(); }

    @BeforeEach
    void setUp() {
        telegram.setApiBase(tg.base());
        settings.update(s -> { s.setTelegramToken("123:abc"); s.setTelegramChatId("111"); });
        tg.status = 200;
        // chờ mọi consumer (kể cả consumer topic thử lại, DLT) được Kafka chia partition xong
        await().atMost(Duration.ofSeconds(60)).until(() -> listeners.getAllListenerContainers().stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
    }

    @AfterEach
    void tearDown() { settings.update(s -> { s.setTelegramToken(""); s.setTelegramChatId(""); }); }

    @Test
    void oneEventManyConsumers() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession ws = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        try {
            BlockingQueue<String> live = new LinkedBlockingQueue<>();
            ws.subscribe("/topic/logs", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) { return String.class; }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) { live.add((String) payload); }
            });
            Thread.sleep(300);
            String today = clock.now().date();
            int before = actions(today, "rule");
            String name = "Camp " + UUID.randomUUID();

            String logId = executor.record(false, e -> { e.setKind("rule"); e.setSource("Rule · CPA cao"); e.setName(name); e.setDetail("Đã tắt"); }).getId();

            // sự kiện nằm trên topic fbads.events, khoá = mã dòng nhật ký, có cờ gửi Telegram
            ConsumerRecord<String, String> rec = find(EventTopics.EVENTS, r -> r.value().contains(logId));
            assertThat(rec.key()).isEqualTo(logId);
            JsonNode ev = json.readTree(rec.value());
            assertThat(ev.get("type").asString()).isEqualTo(AppEvent.LOG_CREATED);
            assertThat(ev.get("telegram").asBoolean()).isTrue();

            // 1) Telegram
            await().atMost(Duration.ofSeconds(20)).until(() -> tg.count("✅ <b>Rule · CPA cao</b>\n" + name + ": Đã tắt") == 1);
            // 2) thống kê
            await().atMost(Duration.ofSeconds(20)).until(() -> actions(today, "rule") == before + 1);
            // 3) WebSocket: đi qua Kafka, không qua Redis pub/sub
            String pushed = live.poll(20, TimeUnit.SECONDS);
            assertThat(pushed).isNotNull();
            assertThat(json.readTree(pushed).get("id").asString()).isEqualTo(logId);
            assertThat(redisSubscribers("fbads:live")).isZero();
        } finally {
            ws.disconnect();
        }

        // báo cáo hằng ngày cũng đi qua consumer Telegram
        String report = "📊 <b>Báo cáo Facebook Ads</b>\n" + UUID.randomUUID();
        events.publish(AppEvent.DAILY_REPORT, "report", true, Map.of("text", report));
        await().atMost(Duration.ofSeconds(20)).until(() -> tg.count(report) == 1);
    }

    /** Telegram lỗi tạm thời (500): thử đủ 3 lần (1 + 2 lần qua topic thử lại) rồi vào DLT */
    @Test
    void transientTelegramErrorsAreRetriedThenDeadLettered() {
        tg.status = 500;
        String name = "Retry " + UUID.randomUUID();
        String logId = executor.record(false, e -> { e.setKind("schedule"); e.setSource("Lịch"); e.setName(name); e.setDetail("Đã bật"); }).getId();

        ConsumerRecord<String, String> dead = find(EventTopics.TELEGRAM_DLT, r -> r.value().contains(logId));
        assertThat(tg.count("✅ <b>Lịch</b>\n" + name + ": Đã bật")).isEqualTo(3);
        assertThat(header(dead, "kafka_exception-cause-fqcn")).endsWith("RetryableFailure");
        assertThat(header(dead, "kafka_exception-message")).contains("Internal Server Error");
    }

    /** Lỗi cố định (400: chat id sai): không thử lại, vào thẳng DLT */
    @Test
    void permanentTelegramErrorsGoStraightToDeadLetter() throws Exception {
        tg.status = 400;
        String name = "Bad chat " + UUID.randomUUID();
        String logId = executor.record(false, e -> { e.setKind("schedule"); e.setSource("Lịch"); e.setName(name); e.setDetail("Đã tắt"); }).getId();

        ConsumerRecord<String, String> dead = find(EventTopics.TELEGRAM_DLT, r -> r.value().contains(logId));
        assertThat(header(dead, "kafka_exception-cause-fqcn")).endsWith("PermanentFailure");
        Thread.sleep(1500); // nếu có thử lại (300 ms) thì đã kịp gọi thêm
        assertThat(tg.count("✅ <b>Lịch</b>\n" + name + ": Đã tắt")).isEqualTo(1);
    }

    /** Kafka có thể giao một sự kiện 2 lần: thống kê chỉ đếm một lần */
    @Test
    void duplicateDeliveryIsCountedOnce() {
        String today = clock.now().date();
        String source = "dup" + UUID.randomUUID().toString().substring(0, 8);
        String dup = eventJson(UUID.randomUUID().toString(), source);
        kafkaTemplate.send(EventTopics.EVENTS, "dup", dup);
        kafkaTemplate.send(EventTopics.EVENTS, "dup", dup);
        // cùng khoá → cùng partition → sự kiện đánh dấu này được đọc sau 2 bản trùng
        kafkaTemplate.send(EventTopics.EVENTS, "dup", eventJson(UUID.randomUUID().toString(), source + "-end"));

        await().atMost(Duration.ofSeconds(20)).until(() -> actions(today, source + "-end") == 1);
        assertThat(actions(today, source)).isEqualTo(1);
    }

    private String eventJson(String id, String kind) {
        return json.writeValueAsString(new AppEvent(id, AppEvent.LOG_CREATED, "dup", clock.millis(), false,
                json.valueToTree(Map.of("id", "x", "kind", kind))));
    }

    private int actions(String day, String source) {
        return stats.ofDay(day).stream().filter(s -> s.getKey().source().equals(source)).mapToInt(s -> s.getActions()).sum();
    }

    /** Số client đang nghe kênh Redis (hỏi thẳng redis-cli trong container) */
    private long redisSubscribers(String channel) throws Exception {
        String[] out = redisServer.execInContainer("redis-cli", "PUBSUB", "NUMSUB", channel).getStdout().trim().split("\\s+");
        return Long.parseLong(out[out.length - 1]);
    }

    /** Đọc topic từ đầu tới khi gặp bản ghi thoả điều kiện (consumer riêng, không ảnh hưởng consumer của app) */
    private ConsumerRecord<String, String> find(String topic, Predicate<ConsumerRecord<String, String>> match) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) {
                    if (match.test(r)) return r;
                    seen.add(r);
                }
            }
        }
        throw new AssertionError("Không thấy bản ghi mong đợi trên " + topic + " (đã đọc " + seen.size() + " bản ghi)");
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? "" : new String(h.value(), StandardCharsets.UTF_8);
    }

}
