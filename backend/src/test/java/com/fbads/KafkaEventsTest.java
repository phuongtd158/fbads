package com.fbads;

import com.fbads.common.Ids;
import com.fbads.engine.ActionExecutor;
import com.fbads.engine.EngineClock;
import com.fbads.event.AppEvent;
import com.fbads.event.EventTopics;
import com.fbads.log.LogKind;
import com.fbads.notify.NotifyTarget;
import com.fbads.notify.NotifyTargetRepository;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.report.ReportService;
import com.fbads.rule.Rule;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EventStatsService;
import com.fbads.settings.SettingsService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
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
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * gửi Telegram lỗi thì thử lại qua topic riêng rồi vào DLT, sự kiện nhận trùng chỉ đếm và gửi Telegram một lần,
 * bản ghi hỏng bị bỏ qua ngay.
 * Thời gian chờ giữa các lần thử rút xuống 300 ms cho test nhanh.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fbads.engine.enabled=false", "fbads.kafka.enabled=true",
        "fbads.kafka.notify.attempts=3", "fbads.kafka.notify.backoff-ms=300"})
class KafkaEventsTest {
    @Container
    @ServiceConnection
    static MySQLContainer db = new MySQLContainer("mysql:8.0");

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
    TelegramChannel telegram;
    @Autowired
    NotifyTargetRepository notifyTargets;
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
    @Autowired
    ReportService reports;

    @BeforeAll
    static void startStub() throws Exception { tg = new TelegramStub(); }

    @AfterAll
    static void stopStub() { tg.close(); }

    WorkspaceContext.Scope scope;

    @BeforeEach
    void setUp() {
        scope = WorkspaceContext.enter(WorkspaceContext.DEFAULT); // gọi service trực tiếp = workspace 1
        telegram.setApiBase(tg.base());
        TestChannels.telegram(notifyTargets, "123:abc", "111");
        tg.status = 200;
        tg.failChat = null;
        // chờ mọi consumer (kể cả consumer topic thử lại, DLT) được Kafka chia partition xong
        await().atMost(Duration.ofSeconds(60)).until(() -> listeners.getAllListenerContainers().stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
    }

    @AfterEach
    void tearDown() {
        TestChannels.clear(notifyTargets);
        scope.close();
    }

    @Test
    void oneEventManyConsumers() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession ws = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        try {
            BlockingQueue<String> live = new LinkedBlockingQueue<>();
            ws.subscribe("/topic/ws.1.logs", collect(live));
            Thread.sleep(300);
            String today = clock.now().date();
            int before = actions(today, "rule");
            String name = "Camp " + UUID.randomUUID();

            String logId = executor.record(TestLogs.entry(LogKind.RULE, "Rule · CPA cao", name, "Đã tắt"), false).getId();

            // sự kiện nằm trên topic fbads.events, có cờ gửi Telegram
            ConsumerRecord<String, String> rec = find(EventTopics.EVENTS, r -> r.value().contains(logId));
            assertThat(rec.key()).isEqualTo("1:logs"); // mọi sự kiện nhật ký của một workspace chung một khoá → đúng thứ tự
            JsonNode ev = json.readTree(rec.value());
            assertThat(ev.get("type").asString()).isEqualTo(AppEvent.LOG_CREATED);
            assertThat(ev.get("notify").asBoolean()).isTrue();

            // 1) Telegram, đúng một lần
            String text = "✅ <b>Rule · CPA cao</b>\n" + name + ": Đã tắt";
            await().atMost(Duration.ofSeconds(20)).until(() -> tg.count(text) == 1);
            // 2) thống kê
            await().atMost(Duration.ofSeconds(20)).until(() -> actions(today, "rule") == before + 1);
            // 3) WebSocket: đi qua Kafka, không qua Redis pub/sub
            String pushed = live.poll(20, TimeUnit.SECONDS);
            assertThat(pushed).isNotNull();
            assertThat(json.readTree(pushed).get("id").asString()).isEqualTo(logId);
            assertThat(redisSubscribers("fbads:live")).isZero();
            // consumer WebSocket không lưu vị trí đã đọc (group chỉ sống cùng lần chạy này)
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
                String liveGroup = listeners.getListenerContainer("fbads-live").getGroupId();
                assertThat(liveGroup).startsWith("fbads-live-");
                assertThat(admin.listConsumerGroupOffsets(liveGroup).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)).isEmpty();
            }
            Thread.sleep(1500);
            assertThat(tg.count(text)).isEqualTo(1);
        } finally {
            ws.disconnect();
        }
    }

    /** Báo cáo hằng ngày: engine tới giờ thì phát report.daily, consumer Telegram gửi; lượt sau cùng ngày không gửi lại */
    @Test
    void dailyReportGoesThroughKafka() throws Exception {
        String before = settings.get().getReportTime();
        settings.update(s -> s.setReportTime("08:00"));
        clock.setClock(Clock.fixed(Instant.parse("2031-03-04T01:02:00Z"), ZoneOffset.UTC)); // 08:02 giờ Việt Nam
        try {
            reports.tick();
            reports.tick();
            await().atMost(Duration.ofSeconds(20)).until(() -> reportsSent() == 1);
            Thread.sleep(1500);
            assertThat(reportsSent()).isEqualTo(1);
            ConsumerRecord<String, String> rec = find(EventTopics.EVENTS, r -> r.value().contains(AppEvent.DAILY_REPORT));
            assertThat(json.readTree(rec.value()).get("notify").asBoolean()).isTrue();
        } finally {
            clock.setClock(Clock.systemUTC());
            settings.update(s -> s.setReportTime(before));
        }
    }

    /** Đổi ngân sách qua API: log.created (thao tác tay, không báo Telegram) và objects.changed đều lên Kafka, giao diện nhận cả hai */
    @Test
    void manualChangeReachesUiThroughKafka() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession ws = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        try {
            BlockingQueue<String> objects = new LinkedBlockingQueue<>();
            BlockingQueue<String> logs = new LinkedBlockingQueue<>();
            ws.subscribe("/topic/ws.1.objects", collect(objects));
            ws.subscribe("/topic/ws.1.logs", collect(logs));
            Thread.sleep(300);
            String today = clock.now().date();
            int before = actions(today, "manual");

            Api api = new Api(port);
            assertThat(api.post("/api/objects/mock_2/budget", Map.of("amount", 444000, "name", "Camp 2")).status()).isEqualTo(200);

            find(EventTopics.EVENTS, r -> r.value().contains(AppEvent.OBJECTS_CHANGED) && r.value().contains("mock_2"));
            String obj = objects.poll(20, TimeUnit.SECONDS);
            assertThat(obj).isNotNull();
            assertThat(json.readTree(obj).get("id").asString()).isEqualTo("mock_2");
            String line = logs.poll(20, TimeUnit.SECONDS);
            assertThat(line).isNotNull();
            assertThat(json.readTree(line).get("kind").asString()).isEqualTo("manual");
            await().atMost(Duration.ofSeconds(20)).until(() -> actions(today, "manual") == before + 1);
        } finally {
            ws.disconnect();
        }
    }

    /** Bản ghi không đọc được: thống kê và WebSocket bỏ qua ngay (không chặn bản ghi sau), Telegram đưa thẳng vào DLT */
    @Test
    void badRecordsAreSkipped() {
        String today = clock.now().date();
        String source = "bad" + UUID.randomUUID().toString().substring(0, 8);
        String broken = "không phải JSON " + UUID.randomUUID();
        kafkaTemplate.send(EventTopics.EVENTS, "bad", broken);
        kafkaTemplate.send(EventTopics.EVENTS, "bad", "{}");
        kafkaTemplate.send(EventTopics.EVENTS, "bad", "null");
        kafkaTemplate.send(EventTopics.EVENTS, "bad", eventJson(UUID.randomUUID().toString(), source, false));

        // trước đây mỗi bản ghi hỏng bị thử lại 5 lần × 2 giây
        await().atMost(Duration.ofSeconds(5)).until(() -> actions(today, source) == 1);
        ConsumerRecord<String, String> dead = find(EventTopics.NOTIFY_DLT, r -> r.value().equals(broken));
        assertThat(header(dead, "kafka_exception-cause-fqcn")).endsWith("BadEventException");
    }

    /** Telegram lỗi tạm thời (500): thử đủ 3 lần (1 + 2 lần qua topic thử lại) rồi vào DLT */
    @Test
    void transientTelegramErrorsAreRetriedThenDeadLettered() {
        tg.status = 500;
        String name = "Retry " + UUID.randomUUID();
        String logId = executor.record(TestLogs.entry(LogKind.SCHEDULE, "Lịch", name, "Đã bật"), false).getId();

        ConsumerRecord<String, String> dead = find(EventTopics.NOTIFY_DLT, r -> r.value().contains(logId));
        assertThat(tg.count("✅ <b>Lịch</b>\n" + name + ": Đã bật")).isEqualTo(3);
        assertThat(header(dead, "kafka_exception-cause-fqcn")).endsWith("Retryable");
        assertThat(header(dead, "kafka_exception-message")).contains("Internal Server Error");
    }

    /**
     * Hai kênh, một kênh lỗi tạm thời: cả sự kiện được thử lại, nhưng kênh đã gửi được không nhận lại
     * (dấu "đã gửi" trong Redis tính theo từng kênh). Kênh lỗi được thử đủ 3 lần.
     */
    @Test
    void onlyTheFailingChannelIsRetried() {
        NotifyTarget broken = TestChannels.telegram(notifyTargets, "123:abc", "111");
        NotifyTarget second = new NotifyTarget(Ids.uid(), TelegramChannel.TYPE);
        second.setName("Nhóm lỗi");
        second.setTopics(broken.getTopics());
        second.setConfig("{\"token\":\"123:abc\",\"chatId\":\"999\"}");
        notifyTargets.save(second);
        tg.failChat = "999";
        String name = "Hai kênh " + UUID.randomUUID();
        String logId = executor.record(TestLogs.entry(LogKind.SCHEDULE, "Lịch", name, "Đã bật"), false).getId();

        find(EventTopics.NOTIFY_DLT, r -> r.value().contains(logId));
        String text = "✅ <b>Lịch</b>\n" + name + ": Đã bật";
        List<String> chats = new ArrayList<>();
        for (int i = 0; i < tg.texts.size(); i++) if (tg.texts.get(i).equals(text)) chats.add(tg.chats.get(i));
        assertThat(chats).filteredOn("111"::equals).hasSize(1);
        assertThat(chats).filteredOn("999"::equals).hasSize(3);
    }

    /** Lỗi cố định (400: chat id sai): không thử lại, vào thẳng DLT */
    @Test
    void permanentTelegramErrorsGoStraightToDeadLetter() throws Exception {
        tg.status = 400;
        String name = "Bad chat " + UUID.randomUUID();
        String logId = executor.record(TestLogs.entry(LogKind.SCHEDULE, "Lịch", name, "Đã tắt"), false).getId();

        ConsumerRecord<String, String> dead = find(EventTopics.NOTIFY_DLT, r -> r.value().contains(logId));
        assertThat(header(dead, "kafka_exception-cause-fqcn")).endsWith("Permanent");
        Thread.sleep(1500); // nếu có thử lại (300 ms) thì đã kịp gọi thêm
        assertThat(tg.count("✅ <b>Lịch</b>\n" + name + ": Đã tắt")).isEqualTo(1);
    }

    /** Kafka có thể giao một sự kiện 2 lần: thống kê chỉ đếm một lần, Telegram chỉ gửi một lần */
    @Test
    void duplicateDeliveryIsHandledOnce() {
        String today = clock.now().date();
        String source = "dup" + UUID.randomUUID().toString().substring(0, 8);
        String dup = eventJson(UUID.randomUUID().toString(), source, true);
        kafkaTemplate.send(EventTopics.EVENTS, "dup", dup);
        kafkaTemplate.send(EventTopics.EVENTS, "dup", dup);
        // cùng khoá → cùng partition → sự kiện đánh dấu này được đọc sau 2 bản trùng
        kafkaTemplate.send(EventTopics.EVENTS, "dup", eventJson(UUID.randomUUID().toString(), source + "-end", true));

        await().atMost(Duration.ofSeconds(20)).until(() -> actions(today, source + "-end") == 1 && tg.count(textOf(source + "-end")) == 1);
        assertThat(actions(today, source)).isEqualTo(1);
        assertThat(tg.count(textOf(source))).isEqualTo(1);
    }

    private String eventJson(String id, String kind, boolean telegram) {
        return json.writeValueAsString(new AppEvent(id, AppEvent.LOG_CREATED, "dup", clock.millis(), telegram,
                json.valueToTree(Map.of("id", "x", "kind", kind, "source", "Lịch", "name", kind, "detail", "Đã tắt"))));
    }

    private static String textOf(String name) { return "✅ <b>Lịch</b>\n" + name + ": Đã tắt"; }

    private long reportsSent() { return tg.texts.stream().filter(t -> t.startsWith("📊 <b>Báo cáo Facebook Ads</b>")).count(); }

    private static StompFrameHandler collect(BlockingQueue<String> into) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return String.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) { into.add((String) payload); }
        };
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
