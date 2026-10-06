package com.fbads;

import com.fbads.client.FbException;
import com.fbads.client.GraphClient;
import com.fbads.common.ApiException;
import com.fbads.common.Ids;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.engine.EngineClock;
import com.fbads.entity.LogEntry;
import com.fbads.repository.LogRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EngineState;
import com.fbads.service.FacebookService;
import com.fbads.service.ReportService;
import com.fbads.service.SettingsService;
import com.fbads.service.TelegramService;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Xu hướng theo ngày và báo cáo tuần (bản Node: tests/trend, phần trend của tests/insightsApi). Facebook và Telegram là máy chủ giả. */
class TrendWeeklyTest extends IntegrationBase {
    @LocalServerPort int port;
    @Autowired SettingsService settings;
    @Autowired FacebookService fb;
    @Autowired TelegramService telegram;
    @Autowired ReportService report;
    @Autowired EngineClock clock;
    @Autowired EngineState state;
    @Autowired LogRepository logRepo;
    @Autowired com.fbads.client.RateLimits limits;

    GraphStub fbStub;
    TelegramStub tg;
    WorkspaceContext.Scope ws;
    final List<String> logIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        fbStub = new GraphStub();
        fbStub.handler = r -> GraphStub.Res.ok("{\"data\":[]}");
        tg = new TelegramStub();
        fb.setGraphBase(fbStub.base());
        telegram.setApiBase(tg.base());
        settings.update(s -> { s.setMock(true); s.setDryRun(false); s.setAccessToken("EAAtoken-1234567890"); s.setAdAccountIds(List.of("111")); s.setAdAccountId("111");
            s.setTelegramToken("1:x"); s.setTelegramChatId("42"); s.setReportTime("08:00"); s.setWeeklyReport(true); s.setTimezone("Asia/Ho_Chi_Minh"); s.setResultAction("purchase"); });
        fb.resetCache();
        fb.resetMock();
        state.clearAll();
    }

    @AfterEach
    void tearDown() {
        logRepo.deleteAllById(logIds);
        settings.update(s -> { s.setMock(true); s.setDryRun(true); s.setAccessToken(""); s.setAdAccountIds(List.of()); s.setAdAccountId(""); s.setTelegramToken(""); s.setTelegramChatId("");
            s.setReportTime(""); });
        fb.resetCache();
        fb.resetMock();
        limits.reset();
        fb.setGraphBase(GraphClient.BASE);
        telegram.setApiBase("https://api.telegram.org");
        clock.setClock(Clock.systemUTC());
        state.clearAll();
        fbStub.close();
        tg.close();
        ws.close();
    }

    static List<Object> col(List<Map<String, Object>> days, String k) { return days.stream().map(d -> d.get(k)).toList(); }

    @Test
    void liveTrendFillsGapsAndCaches() {
        settings.update(s -> s.setMock(false));
        fbStub.handler = r -> GraphStub.Res.ok("{\"data\":[{\"date_start\":\"2026-09-01\",\"spend\":\"100000\",\"impressions\":\"1000\",\"clicks\":\"10\",\"actions\":[{\"action_type\":\"purchase\",\"value\":\"2\"}]},"
                + "{\"date_start\":\"2026-09-03\",\"spend\":\"50000\",\"impressions\":\"500\",\"clicks\":\"5\"}]}");
        FacebookService.TrendResult r = fb.dailyTrend("555", "2026-09-01", "2026-09-04", false);
        assertThat(col(r.days(), "date")).containsExactly("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04");
        assertThat(col(r.days(), "spend")).containsExactly(100000.0, 0.0, 50000.0, 0.0);
        assertThat(col(r.days(), "results")).containsExactly(2.0, 0.0, 0.0, 0.0);
        assertThat(r.days().getFirst().get("cpa")).isEqualTo(50000.0);
        GraphStub.Req q = fbStub.calls.getFirst();
        assertThat(q.path()).isEqualTo("555/insights");
        assertThat(q.params()).containsEntry("time_increment", "1").containsEntry("time_range", "{\"since\":\"2026-09-01\",\"until\":\"2026-09-04\"}");
        fb.dailyTrend("555", "2026-09-01", "2026-09-04", false);
        fb.dailyTrend("555", "2026-09-01", "2026-09-04", true); // bấm Làm mới ngay sau khi tải: vẫn dùng lại
        assertThat(fbStub.calls).hasSize(1);
        // bị Facebook giới hạn: chưa có số cũ thì báo lỗi
        limits.block(60_000);
        assertThatThrownBy(() -> fb.dailyTrend("666", "2026-09-01", "2026-09-02", false)).isInstanceOf(FbException.class).hasMessageContaining("giới hạn số lần gọi");
    }

    @Test
    void mockTrendIsStableAnd404() {
        FacebookService.TrendResult a = fb.dailyTrend("mock_1", "2026-09-01", "2026-09-30", false);
        fb.resetCache();
        FacebookService.TrendResult b = fb.dailyTrend("mock_1", "2026-09-01", "2026-09-30", false);
        assertThat(a.days()).hasSize(30).isEqualTo(b.days());
        assertThatThrownBy(() -> fb.dailyTrend("nope", "2026-09-01", "2026-09-02", false)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(404));
    }

    @Test
    void trendApi() throws Exception {
        Api api = new Api(port);
        Api.Res r = api.get("/api/objects/mock_1/trend");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("days").size()).isEqualTo(30);
        assertThat(r.body().get("until").asString()).isEqualTo(com.fbads.common.DateRanges.todayIn("Asia/Ho_Chi_Minh"));
        assertThat(r.body().get("events").isArray()).isTrue();
        assertThat(r.body().get("days").get(0).has("spend")).isTrue();
        assertThat(api.get("/api/objects/mock_2/trend?days=7").body().get("days").size()).isEqualTo(7);
        assertThat(api.get("/api/objects/mock_1/trend?days=3").status()).isEqualTo(400);
        assertThat(api.get("/api/objects/mock_1/trend?days=abc").status()).isEqualTo(400);
        assertThat(api.get("/api/objects/khong_co/trend").status()).isEqualTo(404);
    }

    @Test
    void lastWeekIsMondayToSunday() {
        assertThat(ReportService.lastWeek(new EngineClock.Now("2026-09-28", 0, 1))).isEqualTo(new ReportService.Week("2026-09-21", "2026-09-27", "2026-09-14", "2026-09-20"));
        assertThat(ReportService.lastWeek(new EngineClock.Now("2026-10-01", 0, 4)).since()).isEqualTo("2026-09-21"); // thứ Năm
        assertThat(ReportService.lastWeek(new EngineClock.Now("2026-10-04", 0, 0)).until()).isEqualTo("2026-09-27"); // Chủ nhật: tuần này chưa hết
    }

    @Test
    void rankCamps() {
        java.util.function.BiFunction<String, double[], ReportService.Row> r = (id, v) -> {
            AdObject o = new AdObject();
            o.id = id;
            return new ReportService.Row(o, new Metrics(v[0], 0, 0, 0, v[1], v[1] > 0 ? v[0] / v[1] : null, 0, null, 0, 0, 0, 0, 0));
        };
        ReportService.Ranked x = ReportService.rankCamps(List.of(r.apply("a", new double[]{100, 10}), r.apply("b", new double[]{100, 2}), r.apply("c", new double[]{300, 0}),
                r.apply("d", new double[]{100, 5}), r.apply("e", new double[]{0, 0}), r.apply("f", new double[]{100, 1}), r.apply("g", new double[]{50, 0})));
        assertThat(x.best().stream().map(y -> y.o().id).toList()).containsExactly("a", "d", "b");
        assertThat(x.worst().stream().map(y -> y.o().id).toList()).containsExactly("c", "g", "f");
    }

    void addLog(String day, String kind, boolean dry, String type) {
        LogEntry l = new LogEntry();
        l.setId(Ids.uid()); l.setTs(Instant.parse(day + "T05:00:00Z")); l.setKind(kind); l.setOk(true); l.setDry(dry); l.setMode("mock"); l.setAction(Map.of("type", type));
        l.setSource("test"); l.setName("x"); l.setDetail("x");
        logIds.add(logRepo.save(l).getId());
    }

    @Test
    void weeklyReportText() {
        addLog("2026-09-22", "rule", false, "off");
        addLog("2026-09-23", "schedule", false, "budget");
        addLog("2026-09-23", "rule", true, "off");      // chạy thử: không tính
        addLog("2026-09-23", "manual", false, "off");   // làm tay: không tính
        addLog("2026-09-29", "rule", false, "on");      // tuần này: không tính
        clock.setClock(Clock.fixed(Instant.parse("2026-09-28T01:00:00Z"), ZoneOffset.UTC));
        assertThat(report.sendWeekly().configured()).isTrue();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).contains("Báo cáo tuần 21/09 – 27/09").contains("Tài khoản mẫu A").contains("Tốt nhất")
                .contains("Tool đã tự thao tác 2 lần: bật 0, tắt 1, đổi ngân sách 1");
    }

    @Test
    void mondayMorningSendsDailyAndWeeklyOnce() {
        clock.setClock(Clock.fixed(Instant.parse("2026-09-28T01:02:00Z"), ZoneOffset.UTC)); // 08:02 thứ Hai giờ Việt Nam
        report.tick();
        assertThat(tg.texts).hasSize(2);
        assertThat(tg.texts.get(1)).contains("Báo cáo tuần");
        report.tick();
        assertThat(tg.texts).as("không gửi lại trong cùng ngày").hasSize(2);

        state.clearAll();
        tg.texts.clear();
        settings.update(s -> s.setWeeklyReport(false));
        report.tick();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).doesNotContain("Báo cáo tuần");
    }

    @Test
    void otherDaysNoWeekly() {
        clock.setClock(Clock.fixed(Instant.parse("2026-09-29T01:02:00Z"), ZoneOffset.UTC)); // thứ Ba
        report.tick();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).doesNotContain("Báo cáo tuần");
    }
}
