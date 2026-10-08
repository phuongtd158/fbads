package com.fbads;

import com.fbads.engine.EngineClock;
import com.fbads.engine.EngineWatch;
import com.fbads.facebook.FacebookActions;
import com.fbads.facebook.FacebookAuth;
import com.fbads.facebook.FacebookGraph;
import com.fbads.facebook.FacebookObjects;
import com.fbads.facebook.FacebookState;
import com.fbads.facebook.FbException;
import com.fbads.facebook.GraphClient;
import com.fbads.log.LogService;
import com.fbads.notify.Notice;
import com.fbads.notify.NotifyTargetRepository;
import com.fbads.notify.SendResult;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.WsState;
import com.fbads.settings.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Chạy 24/7 ổn định: gọi Facebook khi mạng chập chờn, báo token, báo vòng tự động lỗi/kẹt, /api/health (bản Node: tests/fbNetwork, tests/watch). */
class WatchIntegrationTest extends IntegrationBase {
    @LocalServerPort
    int port;
    @Autowired
    SettingsService settings;
    @Autowired FacebookObjects objects;
    @Autowired FacebookActions actions;
    @Autowired FacebookAuth auth;
    @Autowired FacebookGraph fbGraph;
    @Autowired FacebookState fbState;
    @Autowired
    GraphClient graph;
    @Autowired
    TelegramChannel telegram;
    @Autowired
    NotifyTargetRepository notifyTargets;
    @Autowired
    EngineWatch watch;
    @Autowired
    EngineClock clock;
    @Autowired
    WsState state;
    @Autowired
    LogService logs;
    @Autowired
    com.fbads.facebook.RateLimits limits;

    GraphStub fbStub;
    TelegramStub tg;
    WorkspaceContext.Scope ws;
    /** "Bây giờ" của test: 10:00 ngày 4/3/2031 giờ Việt Nam */
    Instant now = Instant.parse("2031-03-04T03:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        fbStub = new GraphStub();
        tg = new TelegramStub();
        fbGraph.setGraphBase(fbStub.base());
        graph.setTimeout(Duration.ofMillis(300));
        telegram.setApiBase(tg.base());
        TestChannels.telegram(notifyTargets, "123:abc", "42");
        clock.setClock(Clock.fixed(now, ZoneOffset.UTC));
        settings.update(s -> { s.setMock(false); s.setAccessToken("EAAtesttoken1234567890"); s.setAdAccountIds(java.util.List.of("123")); s.setAdAccountId("123"); });
        fbState.resetCache();
        watch.reset();
        state.clearAll();
    }

    @AfterEach
    void tearDown() {
        settings.update(s -> { s.setMock(true); s.setAccessToken(""); s.setAdAccountIds(java.util.List.of()); s.setAdAccountId(""); });
        fbState.resetCache();
        limits.reset(); // "rate" đã chặn gọi Facebook 5 phút: không để lây sang test khác
        fbGraph.setGraphBase(GraphClient.BASE);
        graph.setTimeout(GraphClient.TIMEOUT);
        telegram.setApiBase("https://api.telegram.org");
        TestChannels.clear(notifyTargets);
        telegram.setTimeout(TelegramChannel.TIMEOUT);
        clock.setClock(Clock.systemUTC());
        watch.reset();
        state.clearAll();
        fbStub.close();
        tg.close();
        ws.close();
    }

    /** debug_token trả token hết hạn sau `days` ngày (null = không hết hạn) */
    void tokenExpiresIn(Double days) {
        long exp = days == null ? 0 : (System.currentTimeMillis() + (long) (days * 86_400_000)) / 1000; // inspectToken tính số ngày theo giờ thật
        fbStub.handler = r -> GraphStub.Res.ok("{\"data\":{\"is_valid\":true,\"expires_at\":" + exp + ",\"scopes\":[\"ads_management\",\"ads_read\"]}}");
    }

    @Test
    void readsAreRetriedWritesAreNot() {
        tokenExpiresIn(null);
        fbStub.script("hang", "500");
        assertThat(auth.inspectToken("EAAtesttoken1234567890").valid()).as("lần thứ 3 thành công").isEqualTo(true);
        assertThat(fbStub.calls).hasSize(3);

        fbStub.calls.clear();
        fbStub.script("500", "500", "500", "500");
        assertThatThrownBy(() -> auth.inspectToken("EAAtesttoken1234567890")).isInstanceOf(FbException.class);
        assertThat(fbStub.calls).as("không thử quá 2 lần").hasSize(3);

        fbStub.calls.clear();
        fbStub.script("rate");
        assertThatThrownBy(() -> auth.inspectToken("EAAtesttoken1234567890")).isInstanceOf(FbException.class);
        assertThat(fbStub.calls).as("bị giới hạn thì không thử lại").hasSize(1);

        fbStub.calls.clear();
        fbStub.script("hang");
        assertThatThrownBy(() -> actions.setStatus("c1", false)).isInstanceOfSatisfying(FbException.class, e -> {
            assertThat(e.fb().get("timeout")).isEqualTo(true);
            assertThat(e.getMessage()).contains("Không rõ thao tác");
        });
        assertThat(fbStub.calls).hasSize(1);
        assertThat(fbStub.calls.getFirst().method()).isEqualTo("POST");

        fbStub.calls.clear();
        fbStub.script("neterr");
        assertThatThrownBy(() -> actions.setBudget("c1", 500000)).isInstanceOf(FbException.class);
        assertThat(fbStub.calls).as("lời gọi ghi lỗi mạng: không thử lại").hasSize(1);
    }

    @Test
    void telegramTimesOut() {
        telegram.setApiBase(fbStub.base());
        telegram.setTimeout(Duration.ofMillis(300));
        fbStub.script("hang");
        SendResult r = telegram.send(new Notice(Notice.Topic.ALERT, "xin chào"), Api.JSON.readTree("{\"token\":\"123:abc\",\"chatId\":\"42\"}"));
        assertThat(r.recipients().getFirst().ok()).isFalse();
        assertThat(r.recipients().getFirst().error()).contains("không trả lời");
    }

    @Test
    void tokenExpiryWarnedOncePerDay() {
        tokenExpiresIn(30.0);
        watch.tickToken();
        assertThat(tg.texts).as("còn 30 ngày: không báo").isEmpty();

        state.clearAll();
        tokenExpiresIn(3.5);
        watch.tickToken();
        watch.tickToken();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).startsWith("⏳ <b>Token Facebook sắp hết hạn</b>\nCòn 3 ngày");
        int calls = fbStub.calls.size();
        watch.tickToken();
        assertThat(fbStub.calls).as("hôm nay đã kiểm tra: không gọi lại").hasSize(calls);

        clock.setClock(Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC));
        watch.tickToken();
        assertThat(tg.texts).as("sang ngày mới: báo lại").hasSize(2);
    }

    @Test
    void brokenTokenAlertedImmediatelyOnce() {
        fbStub.handler = r -> new GraphStub.Res(400, "{\"error\":{\"code\":190,\"message\":\"Error validating access token\"}}");
        assertThatThrownBy(() -> objects.listObjects(true)).isInstanceOf(FbException.class);
        watch.tickToken();
        watch.tickToken();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).startsWith("❌ <b>Token Facebook không còn dùng được</b>");
        assertThat(logs.recent(1).getFirst().getName()).isEqualTo("Token Facebook");

        // đổi token: hết báo lỗi cũ, token mới hợp lệ thì không báo gì
        settings.update(s -> s.setAccessToken("EAAtokenmoi1234567890"));
        tokenExpiresIn(null);
        watch.tickToken();
        assertThat(tg.texts).hasSize(1);
    }

    @Test
    void tokenCheckNetworkErrorRetriesAfterAnHour() {
        fbStub.script("hang", "hang", "hang"); // hết giờ cả 3 lần (lỗi mạng thì HttpClient của JDK còn tự gọi lại thêm)
        watch.tickToken();
        assertThat(fbStub.calls).hasSize(3);
        watch.tickToken();
        assertThat(fbStub.calls).as("lỗi mạng: chưa thử lại ngay").hasSize(3);
        clock.setClock(Clock.fixed(now.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        tokenExpiresIn(null);
        watch.tickToken();
        assertThat(fbStub.calls).hasSize(4);
        assertThat(tg.texts).isEmpty();
    }

    @Test
    void mockModeSkipsTokenCheck() {
        settings.update(s -> s.setMock(true));
        watch.tickToken();
        assertThat(fbStub.calls).isEmpty();
    }

    @Test
    void errorsInARowAndRecovery() {
        RuntimeException boom = new IllegalStateException("DB lỗi");
        watch.workspaceDone(boom);
        watch.workspaceDone(boom);
        assertThat(tg.texts).isEmpty();
        watch.workspaceDone(boom);
        watch.workspaceDone(boom);
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).startsWith("⚠️ <b>Vòng tự động lỗi 3 lượt liên tiếp</b>\nDB lỗi");
        watch.workspaceDone(null);
        assertThat(tg.texts).hasSize(2);
        assertThat(tg.texts.get(1)).isEqualTo("✅ <b>Vòng tự động đã chạy lại bình thường</b>");
        watch.workspaceDone(null);
        assertThat(tg.texts).hasSize(2);
        // bị Facebook giới hạn số lần gọi: không tính là lỗi
        FbException rate = new FbException("giới hạn", new java.util.LinkedHashMap<>(java.util.Map.of("code", 17)));
        for (int i = 0; i < 4; i++) watch.workspaceDone(rate);
        assertThat(tg.texts).hasSize(2);
    }

    @Test
    void stallAlertedOnceAndHealthDown() throws Exception {
        watch.tickDone();
        assertThat(http("GET", "/api/health").statusCode()).isEqualTo(200);
        assertThat(http("HEAD", "/api/health").statusCode()).as("HEAD cho dịch vụ theo dõi").isEqualTo(200);

        watch.tickStarted();
        watch.workspaceStarted();
        clock.setClock(Clock.fixed(now.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
        watch.checkStall();
        watch.checkStall();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).startsWith("⚠️ <b>Vòng tự động bị kẹt</b>\nMột lượt đã chạy 6 phút");
        HttpResponse<String> h = http("GET", "/api/health");
        assertThat(h.statusCode()).isEqualTo(503);
        assertThat(h.body()).contains("\"ok\":false");

        watch.workspaceDone(null);
        watch.tickDone();
        assertThat(tg.texts.get(1)).isEqualTo("✅ <b>Vòng tự động đã chạy lại bình thường</b>");
        assertThat(http("GET", "/api/health").statusCode()).isEqualTo(200);

        clock.setClock(Clock.fixed(now.plus(Duration.ofMinutes(12)), ZoneOffset.UTC));
        assertThat(http("GET", "/api/health").statusCode()).as("5 phút không xong lượt nào").isEqualTo(503);
    }

    private HttpResponse<String> http(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }
}
