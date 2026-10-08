package com.fbads;

import com.fbads.account.AuthService;
import com.fbads.account.UserRepository;
import com.fbads.account.WorkspaceRepository;
import com.fbads.config.CacheConfig;
import com.fbads.engine.ActionExecutor;
import com.fbads.engine.EngineClock;
import com.fbads.engine.EngineLock;
import com.fbads.event.EventStatsService;
import com.fbads.facebook.FbSnapshots;
import com.fbads.log.LogEntry;
import com.fbads.log.LogKind;
import com.fbads.log.LogService;
import com.fbads.notify.NotifyTargetRepository;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.report.ReportService;
import com.fbads.schedule.ScheduleRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.settings.SettingsService;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy cả ứng dụng trên MySQL thật (Testcontainers), dữ liệu giả (mock), engine tắt.
 * Gọi API y như giao diện Vue gọi.
 */
class ApiIntegrationTest extends IntegrationBase {
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
    TelegramChannel telegram;
    @Autowired
    NotifyTargetRepository notifyTargets;
    @Autowired
    EventStatsService stats;
    @Autowired
    EngineClock clock;
    @Autowired
    ReportService reports;
    @Autowired
    AuthService auth;
    @Autowired
    UserRepository users;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    ScheduleRepository scheduleRepo;
    @Autowired
    JdbcTemplate jdbc;
    Api api;
    /** Gọi service trực tiếp trong test = thao tác trên workspace 1 (như chế độ mở) */
    WorkspaceContext.Scope ws;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        ws.close();
        resetAccounts();
    }

    /** Về lại chế độ mở (chưa có tài khoản, chỉ workspace 1) cho test sau */
    void resetAccounts() {
        WorkspaceContext.run(WorkspaceContext.DEFAULT, () -> scheduleRepo.findAll().stream().filter(x -> x.getName().contains("[test-ws]")).forEach(scheduleRepo::delete));
        users.deleteAll();
        workspaces.findAll().stream().filter(w -> w.getId() != WorkspaceContext.DEFAULT).forEach(workspaces::delete);
        auth.recheckUsers();
        redis.delete(redis.keys("fbads:login:*"));
    }

    /** Tạo tài khoản đầu tiên (chủ workspace 1) bằng api, api đăng nhập luôn */
    void setupOwner() {
        Api.Res r = api.post("/api/setup", Map.of("username", "chu", "name", "Chủ", "password", "MatKhau@2026"));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
    }

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

    /** Body đọc thẳng vào DTO: sai kiểu → 400 kèm tên trường; JSON hỏng → báo JSON không hợp lệ; body trống = gửi {} */
    @Test
    void requestBodiesAreTyped() {
        Api.Res status = api.post("/api/objects/mock_1/status", Map.of("on", Map.of("x", 1)));
        assertThat(status.status()).isEqualTo(400);
        assertThat(status.body().get("error").asString()).isEqualTo("on: Sai kiểu dữ liệu");
        assertThat(status.body().get("errors").get("on").asString()).isEqualTo("Sai kiểu dữ liệu");
        assertThat(api.post("/api/workspaces/switch", Map.of("id", "abc")).body().get("errors").has("id")).isTrue();
        assertThat(api.postRaw("/api/logs/x/undo", "{\"force\":").body().get("error").asString()).contains("JSON không hợp lệ");
        Api.Res empty = api.postRaw("/api/setup", "");
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body().get("errors").has("username")).isTrue(); // service vẫn báo lỗi từng trường như gửi {}

        Api.Res rule = api.post("/api/rules", Map.of("conditions", List.of(Map.of("metric", List.of("cpa")))));
        assertThat(rule.body().get("errors").get("conditions[0].metric").asString()).isEqualTo("Sai kiểu dữ liệu");
        assertThat(api.post("/api/schedules", Map.of("days", "abc")).body().get("errors").has("days")).isTrue();
        // số gửi dạng chữ vẫn đọc như bản Node: "abc" là ngưỡng chưa nhập đúng, không phải sai kiểu
        Api.Res preview = api.post("/api/rules/preview", Map.of("metric", "cpa", "op", ">", "value", "abc"));
        assertThat(preview.body().get("errors").get("value").asString()).isEqualTo("Nhập ngưỡng so sánh");
        assertThat(api.postRaw("/api/rules/preview", "").body().get("errors").has("action")).isTrue();

        // Cài đặt (PATCH): gửi null là xoá, không gửi là giữ nguyên. Cuối test trả lại như cũ (cài đặt workspace 1 dùng chung giữa các test).
        JsonNode before = api.get("/api/state").body().get("settings");
        assertThat(api.post("/api/settings", Map.of("reportTime", "08:00")).body().get("reportTime").asString()).isEqualTo("08:00");
        int interval = before.get("ruleIntervalMin").asInt();
        assertThat(api.post("/api/settings", Map.of("ruleIntervalMin", interval)).body().get("reportTime").asString()).isEqualTo("08:00");
        java.util.Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("reportTime", null);
        assertThat(api.post("/api/settings", clear).body().get("reportTime").asString()).isEmpty();
        assertThat(api.post("/api/settings", Map.of("ruleIntervalMin", "abc")).body().get("errors").has("ruleIntervalMin")).isTrue();
        assertThat(api.post("/api/settings", Map.of("accountTargets", 5)).body().get("errors").get("accountTargets").asString()).isEqualTo("Sai kiểu dữ liệu");
        Api.Res targets = api.post("/api/settings", Map.of("accountTargets", Map.of("act_1", Map.of("cpa", "50000", "roas", ""))));
        assertThat(targets.body().get("accountTargets").get("act_1").get("cpa").asLong()).isEqualTo(50000);
        assertThat(targets.body().get("accountTargets").get("act_1").has("roas")).isFalse();
        assertThat(api.post("/api/settings", Map.of("reportTime", before.get("reportTime").asString(), "accountTargets", Api.JSON.convertValue(before.get("accountTargets"), Map.class))).status()).isEqualTo(200);
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
    void accountsLoginAndGuards() {
        assertThat(api.get("/api/auth").body().get("setup").asBoolean()).isTrue(); // chưa có tài khoản: chế độ mở
        assertThat(api.post("/api/setup", Map.of("username", "chu", "password", "12345678")).body().get("errors").has("password")).isTrue();
        setupOwner();
        assertThat(api.post("/api/setup", Map.of("username", "khac", "password", "MatKhau@2026")).status()).isEqualTo(409); // chỉ 1 lần

        JsonNode me = api.get("/api/auth").body();
        assertThat(me.get("user").get("username").asString()).isEqualTo("chu");
        assertThat(me.get("workspace").get("id").asLong()).isEqualTo(1);
        assertThat(me.get("workspace").get("role").asString()).isEqualTo("OWNER");

        Api stranger = new Api(port);
        assertThat(stranger.get("/api/state").status()).isEqualTo(401);
        assertThat(stranger.get("/api/auth").body().get("required").asBoolean()).isTrue();
        assertThat(stranger.post("/api/login", Map.of("username", "chu", "password", "sai")).status()).isEqualTo(401);
        assertThat(stranger.post("/api/login", Map.of("username", "CHU ", "password", "MatKhau@2026")).status()).isEqualTo(200);
        assertThat(stranger.get("/api/state").status()).isEqualTo(200);
        assertThat(stranger.post("/api/logout", Map.of()).status()).isEqualTo(200);
        assertThat(stranger.get("/api/state").status()).isEqualTo(401);

        // đổi mật khẩu: phải đúng mật khẩu cũ; phiên đang dùng vẫn chạy (đăng nhập lại tự động), phiên khác bị đăng xuất
        Api other = new Api(port);
        assertThat(other.post("/api/login", Map.of("username", "chu", "password", "MatKhau@2026")).status()).isEqualTo(200);
        assertThat(api.post("/api/password", Map.of("currentPassword", "sai", "newPassword", "MatKhau@2027")).status()).isEqualTo(400);
        assertThat(api.post("/api/password", Map.of("currentPassword", "MatKhau@2026", "newPassword", "12345678")).status()).isEqualTo(400);
        assertThat(api.post("/api/password", Map.of("currentPassword", "MatKhau@2026", "newPassword", "MatKhau@2027")).status()).isEqualTo(200);
        assertThat(api.get("/api/state").status()).isEqualTo(200);
        assertThat(other.get("/api/state").status()).isEqualTo(401);
    }

    /**
     * Nhiều người dùng: mỗi workspace một bộ dữ liệu riêng, quyền theo vai trò.
     * Chủ workspace 1 thêm nhân viên (EDITOR) và người xem (VIEWER), rồi tạo workspace thứ hai cho khách hàng khác.
     */
    @Test
    void workspacesAreIsolatedAndRolesEnforced() {
        setupOwner();
        Map<String, Object> sch = Map.of("name", "Tắt [test-ws]", "action", "off", "times", List.of("04:17"),
                "days", List.of(0, 1, 2, 3, 4, 5, 6), "targetMode", "list", "targets", List.of("mock_1"), "enabled", true);
        String ws1Schedule = api.post("/api/schedules", sch).body().get("id").asString();
        Map<String, Object> renamed = new java.util.HashMap<>(sch);
        renamed.put("id", ws1Schedule);
        renamed.put("name", "Tắt sửa [test-ws]");
        Api.Res edited = api.post("/api/schedules", renamed); // sửa mục đã có: giữ id, workspace không đổi
        assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
        assertThat(edited.body().get("id").asString()).isEqualTo(ws1Schedule);
        assertThat(api.post("/api/objects/mock_1/budget", Map.of("amount", 111000, "name", "Camp 1")).status()).isEqualTo(200);

        // thành viên: tạo tài khoản mới cần mật khẩu ban đầu; người đã là thành viên thì báo lỗi
        assertThat(api.post("/api/members", Map.of("username", "nhanvien", "role", "EDITOR")).body().get("errors").has("password")).isTrue();
        Api.Res added = api.post("/api/members", Map.of("username", "nhanvien", "name", "Nhân viên", "password", "NhanVien@2026", "role", "EDITOR"));
        assertThat(added.status()).as(String.valueOf(added.body())).isEqualTo(200);
        assertThat(api.post("/api/members", Map.of("username", "xem", "password", "NguoiXem@2026", "role", "VIEWER")).status()).isEqualTo(200);
        assertThat(api.post("/api/members", Map.of("username", "nhanvien", "role", "VIEWER")).status()).isEqualTo(400);
        assertThat(api.get("/api/members").body().size()).isEqualTo(3);
        long ownerId = api.get("/api/auth").body().get("user").get("id").asLong();
        assertThat(api.delete("/api/members/" + ownerId).status()).isEqualTo(400); // chủ cuối cùng
        assertThat(api.post("/api/register", Map.of("username", "la", "password", "NguoiLa@2026")).status()).isEqualTo(403); // chưa bật ALLOW_SIGNUP

        // workspace thứ hai: trống, cài đặt mặc định, dữ liệu riêng
        Api.Res created = api.post("/api/workspaces", Map.of("name", "Khách B"));
        assertThat(created.status()).isEqualTo(200);
        long ws2 = created.body().get("id").asLong();
        JsonNode st2 = api.get("/api/state").body();
        assertThat(st2.get("schedules").size()).isZero();
        assertThat(st2.get("settings").get("mock").asBoolean()).isTrue();
        assertThat(api.get("/api/logs").body().size()).isZero();
        assertThat(api.post("/api/settings", Map.of("ruleIntervalMin", 45)).status()).isEqualTo(200);
        String ws2Schedule = api.post("/api/schedules", Map.of("name", "Lịch WS2 [test-ws]", "action", "on", "times", List.of("07:00"),
                "days", List.of(1), "targetMode", "list", "targets", List.of("mock_2"), "enabled", true)).body().get("id").asString();
        // dữ liệu giả của mỗi workspace riêng: đổi ngân sách ở workspace 1 không thấy ở đây
        JsonNode camp1 = api.get("/api/objects").body().get("items").values().stream().filter(o -> o.get("id").asString().equals("mock_1")).findFirst().orElseThrow();
        assertThat(camp1.get("dailyBudget").asLong()).isNotEqualTo(111000);

        assertThat(api.post("/api/workspaces/switch", Map.of("id", 1)).status()).isEqualTo(200);
        JsonNode st1 = api.get("/api/state").body();
        assertThat(st1.get("schedules").values()).extracting(x -> x.get("id").asString()).containsExactly(ws1Schedule);
        assertThat(st1.get("settings").get("ruleIntervalMin").asInt()).isEqualTo(15);
        assertThat(api.get("/api/auth").body().get("workspaces").size()).isEqualTo(2);

        // nhân viên (EDITOR): sửa lịch được, cài đặt và thành viên thì không; không vào được workspace B
        Api nv = new Api(port);
        assertThat(nv.post("/api/login", Map.of("username", "nhanvien", "password", "NhanVien@2026")).status()).isEqualTo(200);
        assertThat(nv.get("/api/auth").body().get("workspace").get("role").asString()).isEqualTo("EDITOR");
        Map<String, Object> nvSch = new java.util.HashMap<>(sch);
        nvSch.put("name", "Nhân viên [test-ws]");
        nvSch.put("times", List.of("04:18"));
        assertThat(nv.post("/api/schedules", nvSch).status()).isEqualTo(200);
        assertThat(nv.post("/api/settings", Map.of("ruleIntervalMin", 30)).status()).isEqualTo(403);
        assertThat(nv.post("/api/members", Map.of("username", "x", "password", "MatKhau@2026", "role", "OWNER")).status()).isEqualTo(403);
        assertThat(nv.get("/api/members").status()).isEqualTo(200);
        assertThat(nv.post("/api/workspaces/switch", Map.of("id", ws2)).status()).isEqualTo(403);
        // id của workspace khác: như không tồn tại (xoá không được, lưu thì thành mục mới với id khác)
        nv.delete("/api/schedules/" + ws2Schedule);
        Map<String, Object> hijack = new java.util.HashMap<>(nvSch);
        hijack.put("id", ws2Schedule);
        hijack.put("name", "Chiếm [test-ws]");
        hijack.put("times", List.of("04:19"));
        Api.Res hij = nv.post("/api/schedules", hijack);
        assertThat(hij.status()).isEqualTo(200);
        assertThat(hij.body().get("id").asString()).isNotEqualTo(ws2Schedule);
        assertThat(nv.post("/api/schedules/" + ws2Schedule + "/run", Map.of()).status()).isEqualTo(404);
        assertThat(nv.get("/api/logs").body().values()).noneMatch(l -> l.path("name").asString("").contains("WS2"));

        // người xem (VIEWER): chỉ đọc
        Api xem = new Api(port);
        assertThat(xem.post("/api/login", Map.of("username", "xem", "password", "NguoiXem@2026")).status()).isEqualTo(200);
        assertThat(xem.get("/api/state").status()).isEqualTo(200);
        Api.Res denied = xem.post("/api/schedules", sch);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().get("error").asString()).contains("chỉ có quyền xem");
        assertThat(xem.post("/api/objects/mock_1/status", Map.of("on", false)).status()).isEqualTo(403);

        // chủ hạ quyền nhân viên xuống VIEWER: có hiệu lực ngay ở request sau
        long nvId = api.get("/api/members").body().values().stream().filter(m -> m.get("username").asString().equals("nhanvien")).findFirst().orElseThrow().get("userId").asLong();
        assertThat(api.post("/api/members/" + nvId + "/role", Map.of("role", "VIEWER")).status()).isEqualTo(200);
        assertThat(nv.post("/api/schedules", sch).status()).isEqualTo(403);
        // gỡ khỏi workspace: không còn workspace nào → không đọc được dữ liệu
        assertThat(api.delete("/api/members/" + nvId).status()).isEqualTo(200);
        assertThat(nv.get("/api/state").status()).isEqualTo(403);

        // workspace B vẫn còn nguyên lịch của nó
        assertThat(api.post("/api/workspaces/switch", Map.of("id", ws2)).status()).isEqualTo(200);
        JsonNode ws2List = api.get("/api/state").body().get("schedules");
        assertThat(ws2List.values()).extracting(x -> x.get("id").asString()).containsExactly(ws2Schedule);
        assertThat(ws2List.get(0).get("name").asString()).isEqualTo("Lịch WS2 [test-ws]");
        assertThat(api.get("/api/state").body().get("settings").get("ruleIntervalMin").asInt()).isEqualTo(45);
    }

    /** Có SECRET_KEY: token lưu trong DB đã mã hoá (cả cấu hình kênh thông báo), API và engine vẫn đọc ra đúng token */
    @Test
    void secretsAreEncryptedInDb() {
        settings.update(s -> s.setAccessToken("EAAB-token-that"));
        String channel = TestChannels.telegram(notifyTargets, "123:bi-mat", "111").getId();
        try {
            assertThat(jdbc.queryForObject("SELECT access_token FROM app_settings WHERE id = 1", String.class))
                    .startsWith("enc:v1:").doesNotContain("EAAB");
            assertThat(jdbc.queryForObject("SELECT config FROM notify_targets WHERE id = ?", String.class, channel))
                    .startsWith("enc:v1:").doesNotContain("bi-mat");
            settings.reload();
            assertThat(settings.get().getAccessToken()).isEqualTo("EAAB-token-that");
            assertThat(api.get("/api/state").body().get("settings").get("has_accessToken").asBoolean()).isTrue();
            assertThat(notifyTargets.findById(channel).orElseThrow().getConfig()).contains("123:bi-mat");
        } finally {
            settings.update(s -> s.setAccessToken(""));
            TestChannels.clear(notifyTargets);
        }
    }

    /** Số camp tải từ Facebook được lưu ở Redis; đổi ngân sách thì bản ở Redis bị xoá */
    @Test
    void objectsAreCachedInRedis() {
        assertThat(api.get("/api/objects?refresh=1").status()).isEqualTo(200);
        var objects = caches.getCache(CacheConfig.OBJECTS);
        FbSnapshots.Objects saved = objects.get("ws1|mock", FbSnapshots.Objects.class);
        assertThat(saved).isNotNull();
        assertThat(saved.items()).anyMatch(o -> o.id().equals("mock_1"));
        assertThat(redis.keys("fbads:cache:fb-objects::*")).isNotEmpty();

        assertThat(api.post("/api/objects/mock_2/budget", Map.of("amount", 222000, "name", "Camp 2")).status()).isEqualTo(200);
        assertThat(objects.get("ws1|mock")).isNull();
    }

    /** Vòng tự động và nút "Chạy ngay" (EngineLock) dùng chung khoá của workspace trên Redis; workspace khác không bị chặn */
    @Test
    void engineLockIsSharedThroughRedis() {
        LockConfiguration other = new LockConfiguration(Instant.now(), EngineLock.nameOf(1), Duration.ofMinutes(1), Duration.ZERO);
        LockConfiguration otherWs = new LockConfiguration(Instant.now(), EngineLock.nameOf(2), Duration.ofMinutes(1), Duration.ZERO);
        boolean blocked = engineLock.run(() -> locks.lock(other).isEmpty());
        assertThat(blocked).isTrue();
        boolean otherBlocked = engineLock.run(() -> locks.lock(otherWs).map(l -> { l.unlock(); return false; }).orElse(true));
        assertThat(otherBlocked).isFalse();
        var after = locks.lock(other); // chạy xong thì khoá được nhả
        assertThat(after).isPresent();
        after.get().unlock();
    }

    /** Phiên đăng nhập nằm ở Redis; nhập sai 5 lần thì khoá 15 phút (đếm ở Redis) */
    @Test
    void sessionsAndLoginLockoutInRedis() {
        setupOwner();
        Api stranger = new Api(port);
        assertThat(stranger.post("/api/login", Map.of("username", "chu", "password", "MatKhau@2026")).status()).isEqualTo(200);
        assertThat(redis.keys("fbads:session:sessions:*")).isNotEmpty();

        redis.delete(redis.keys("fbads:login:*")); // test khác có thể đã nhập sai
        Api attacker = new Api(port);
        for (int i = 0; i < 5; i++) assertThat(attacker.post("/api/login", Map.of("username", "chu", "password", "sai")).status()).isEqualTo(401);
        Api.Res locked = attacker.post("/api/login", Map.of("username", "chu", "password", "MatKhau@2026"));
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.body().get("error").asString()).contains("15 phút");
        String lockKey = redis.keys("fbads:login:lock:*").iterator().next();
        assertThat(redis.getExpire(lockKey)).isBetween(1L, 900L);
    }

    /** Đổi ngân sách → trình duyệt đang nghe WebSocket nhận ngay dòng nhật ký mới và sự kiện camp đổi */
    @Test
    void liveEventsOverWebSocket() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession stomp = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        try {
            BlockingQueue<String> logs = new LinkedBlockingQueue<>(), objects = new LinkedBlockingQueue<>();
            stomp.subscribe("/topic/ws.1.logs", collect(logs));
            stomp.subscribe("/topic/ws.1.objects", collect(objects));
            Thread.sleep(300); // chờ SUBSCRIBE tới server

            assertThat(api.post("/api/objects/mock_3/budget", Map.of("amount", 333000, "name", "Camp 3")).status()).isEqualTo(200);
            JsonNode obj = Api.JSON.readTree(objects.poll(10, TimeUnit.SECONDS));
            assertThat(obj.get("id").asString()).isEqualTo("mock_3");
            JsonNode log = Api.JSON.readTree(logs.poll(10, TimeUnit.SECONDS));
            assertThat(log.get("after").get("dailyBudget").asLong()).isEqualTo(333000);
            assertThat(log.get("id").asString()).isEqualTo(api.get("/api/logs").body().get(0).get("id").asString());
        } finally {
            stomp.disconnect();
        }

        // Đã có tài khoản mà chưa đăng nhập thì không mở được WebSocket
        setupOwner();
        var stranger = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stranger.get(10, TimeUnit.SECONDS)).hasMessageContaining("401");
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
            TestChannels.telegram(notifyTargets, "123:abc", "111");
            String today = clock.now().date();
            int before = actions(today, "schedule");
            int manualBefore = actions(today, "manual");

            executor.record(TestLogs.entry(LogKind.SCHEDULE, "Lịch · Tắt đêm", "Camp 1", "Đã tắt"), false);
            assertThat(tg.texts).containsExactly("✅ <b>Lịch · Tắt đêm</b>\nCamp 1: Đã tắt");

            executor.record(TestLogs.entry(LogKind.SCHEDULE, "Lịch · Tắt đêm", "-", "Không có gì để làm"), true);
            logs.add(TestLogs.entry(LogKind.MANUAL, "Thủ công", "Camp 1", "Tắt"));
            assertThat(tg.texts).hasSize(1); // im lặng và thao tác tay: không báo
            assertThat(actions(today, "schedule")).isEqualTo(before + 2);
            assertThat(actions(today, "manual")).isEqualTo(manualBefore + 1);

            tg.status = 500; // Telegram lỗi: không có Kafka để thử lại, chỉ ghi log, việc ghi nhật ký vẫn xong
            assertThat(executor.record(TestLogs.entry(LogKind.RULE, "Rule", "Camp 2", "Đã tắt"), false).getId()).isNotNull();
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
            telegram.setApiBase("https://api.telegram.org");
            TestChannels.clear(notifyTargets);
        }
    }

    /** Giờ lưu trong MySQL là UTC (connectionTimeZone=UTC), đọc ra đúng thời điểm đã ghi */
    @Test
    void timesAreStoredInUtc() {
        LogEntry saved = logs.add(TestLogs.entry(LogKind.MANUAL, "Thủ công", "Giờ UTC", "-"));
        LocalDateTime raw = jdbc.queryForObject("SELECT ts FROM logs WHERE id = ?", LocalDateTime.class, saved.getId());
        assertThat(Duration.between(raw, LocalDateTime.now(ZoneOffset.UTC)).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(logs.find(saved.getId()).orElseThrow().getTs()).isEqualTo(saved.getTs());
    }

    private int actions(String day, String source) {
        return stats.ofDay(day).stream().filter(s -> s.getKey().source().equals(source)).mapToInt(s -> s.getActions()).sum();
    }
}
