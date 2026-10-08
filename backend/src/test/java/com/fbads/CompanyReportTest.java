package com.fbads;

import com.fbads.ads.AdLevel;
import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;
import com.fbads.common.ApiException;
import com.fbads.company.CompanyApi;
import com.fbads.company.CompanyConfig;
import com.fbads.company.CompanyConfigPatch;
import com.fbads.company.CompanyConfigRepository;
import com.fbads.company.CompanyLogin;
import com.fbads.company.CompanyReport;
import com.fbads.company.CompanyReportPatch;
import com.fbads.company.CompanyReportRepository;
import com.fbads.company.CompanyReportService;
import com.fbads.company.CompanyRules;
import com.fbads.engine.EngineClock;
import com.fbads.facebook.FacebookInsights;
import com.fbads.facebook.FacebookObjects;
import com.fbads.facebook.FacebookState;
import com.fbads.log.LogEntry;
import com.fbads.log.LogKind;
import com.fbads.log.LogService;
import com.fbads.notify.NotifyTargetRepository;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EngineState;
import com.fbads.settings.SettingsService;
import com.fbads.validation.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * Báo cáo lên hệ thống công ty (bản Node: tests/companyReport, trừ phần nút Telegram vì bản Java không có Telegram hai chiều).
 * Hệ thống công ty và Telegram đều là máy chủ giả: không bao giờ gọi hệ thống thật. Số Facebook cố định qua mock của FacebookObjects / FacebookInsights.
 */
class CompanyReportTest extends IntegrationBase {
    @LocalServerPort int port;
    @Autowired FacebookObjects realObjects;
    @Autowired FacebookInsights realInsights;
    @Autowired FacebookState fbState;
    /** Số Facebook cố định: mock gắn thẳng vào CompanyDrafts (không dùng @MockitoSpyBean để mọi lớp test vẫn chung một Spring context) */
    FacebookObjects objects;
    FacebookInsights insights;
    @Autowired CompanyReportService company;
    @Autowired CompanyApi api;
    @Autowired CompanyConfigRepository configs;
    @Autowired CompanyReportRepository reports;
    @Autowired SettingsService settings;
    @Autowired TelegramChannel telegram;
    @Autowired NotifyTargetRepository notifyTargets;
    @Autowired EngineClock clock;
    @Autowired EngineState state;
    @Autowired LogService logs;

    static final CompanyConfig.Team TEAM = new CompanyConfig.Team("team-1", "CT01", "WDC - Hoạt Huyết", List.of("mock_a"), "");
    static final Map<String, Long> FULL = metrics(1, 2, 3, 4, 5, 6, 7);

    CompanyStub co;
    TelegramStub tg;
    WorkspaceContext.Scope ws;

    static Map<String, Long> metrics(long spend, long messages, long phones, long orders, long dso, long impressions, long clicks) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("spend", spend); m.put("messages", messages); m.put("phones", phones); m.put("orders", orders); m.put("dso_after", dso);
        m.put("impressions", impressions); m.put("clicks", clicks);
        return m;
    }

    @BeforeEach
    void setUp() throws Exception {
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        co = new CompanyStub();
        tg = new TelegramStub();
        telegram.setApiBase(tg.base());
        TestChannels.telegram(notifyTargets, "123:abc", "42");
        settings.update(s -> { s.setMock(true); s.setDryRun(true); });
        reports.deleteAll();
        configure(c -> {
            c.setEnabled(true); c.setMode("approve"); c.setSlots(new ArrayList<>(CompanyRules.SLOTS)); c.setLeadMin(0); c.setBaseUrl(co.base());
            c.setEmail("mkt@congty.vn"); c.setPassword(CompanyStub.GOOD_LOGIN); c.setTeams(List.of(TEAM));
        });
        state.clearAll();
        api.reset();
        fbState.resetMock();
        fbState.resetCache();
        at("2026-10-04T10:00:00Z");
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(drafts(), "objects", realObjects);
        ReflectionTestUtils.setField(drafts(), "insights", realInsights);
        reports.deleteAll();
        configs.deleteAll();
        api.reset();
        settings.update(s -> { s.setMock(true); s.setDryRun(true); });
        telegram.setApiBase("https://api.telegram.org");
        TestChannels.clear(notifyTargets);
        clock.setClock(Clock.systemUTC());
        state.clearAll();
        co.close();
        tg.close();
        ws.close();
    }

    void configure(java.util.function.Consumer<CompanyConfig> f) {
        CompanyConfig c = company.config();
        f.accept(c);
        configs.save(c);
    }

    /** Đặt "bây giờ" (giờ UTC; giờ Việt Nam = +7) */
    void at(String isoUtc) { clock.setClock(Clock.fixed(Instant.parse(isoUtc), ZoneOffset.UTC)); }

    /** Giờ Việt Nam của ngày date */
    void atVn(String date, int hour, int minute) { at(java.time.LocalDateTime.parse(date + "T00:00").plusHours(hour - 7).plusMinutes(minute) + ":00Z"); }

    CompanyReport reload(CompanyReport r) { return reports.findById(r.getId()).orElseThrow(); }

    CompanyReportPatch patch(Map<String, Object> m, String notes) {
        CompanyReportPatch p = new CompanyReportPatch();
        if (m != null) p.setMetrics(m);
        if (notes != null) p.setNotes(notes);
        return p;
    }

    LogEntry lastLog() { return logs.recent(1).getFirst(); }

    // ------------------------------------------------------------------ Luật
    @Test
    void rules() {
        assertThat(CompanyRules.reportDate(9, "2026-10-01")).isEqualTo("2026-09-30");
        assertThat(CompanyRules.submitDate(9, "2026-09-30")).isEqualTo("2026-10-01");
        assertThat(CompanyRules.reportDate(17, "2026-10-01")).isEqualTo("2026-10-01");
        assertThat(CompanyRules.updatesExisting(9)).isTrue();
        assertThat(CompanyRules.updatesExisting(12)).isFalse();
        assertThat(CompanyRules.closeReason("2026-10-04")).isEqualTo("Chốt số liệu cả ngày 04/10");
        assertThat(CompanyRules.validateReason(CompanyRules.closeReason("2026-10-04"))).isEmpty();
        assertThat(CompanyRules.rangeOf(9)).isEqualTo("yesterday");
        assertThat(CompanyRules.rangeOf(22)).isEqualTo("today");
        assertThat(CompanyRules.fireMinute(9, 30)).isEqualTo(8 * 60 + 30);
        assertThat(CompanyRules.fireMinute(9, 999)).as("tối đa 60 phút").isEqualTo(8 * 60);

        List<AdObject> objs = List.of(camp("1", "A", "CT01 Hoạt huyết - Mess"), camp("2", "A", "CT02 Xương khớp"), camp("3", "B", "ct01 retarget"), adset("4", "A", "CT01 nhóm"));
        java.util.function.Function<CompanyConfig.Team, List<String>> ids = t -> CompanyRules.teamCampaigns(t, objs).stream().map(o -> o.id()).toList();
        assertThat(ids.apply(new CompanyConfig.Team("t", "", "", List.of("A"), ""))).containsExactly("1", "2");
        assertThat(ids.apply(new CompanyConfig.Team("t", "", "", List.of(), "CT01"))).containsExactly("1", "3");
        assertThat(ids.apply(new CompanyConfig.Team("t", "", "", List.of("A"), "ct01, xương"))).containsExactly("1", "2");
        assertThat(ids.apply(new CompanyConfig.Team("t", "", "", List.of(), ""))).isEmpty();

        Map<String, Metrics> data = Map.of("1", m(100.4, 3, 2, 2, 500000.4, 1000, 10), "2", m(200.4, 1, 0, 1, 250000, 500, 5));
        assertThat(CompanyRules.sumMetrics(List.of(camp("1", "A", ""), camp("2", "A", ""), camp("3", "A", "")), data)).isEqualTo(metrics(301, 4, 2, 3, 750000, 1500, 15));
    }

    @Test
    void anomaliesAndPatches() {
        CompanyReport r = new CompanyReport("x", "t", "2026-10-04", 17, Instant.now());
        r.setMetrics(new LinkedHashMap<>(FULL));
        r.setCampaigns(List.of("a"));
        assertThat(CompanyRules.anomalies(r)).isEmpty();
        r.getMetrics().put("dso_after", 0L);
        assertThat(CompanyRules.anomalies(r).getFirst()).contains("doanh thu bằng 0");
        r.getMetrics().put("orders", 0L);
        assertThat(CompanyRules.anomalies(r)).isEmpty();
        r.setCampaigns(List.of());
        assertThat(CompanyRules.anomalies(r).getFirst()).contains("không khớp chiến dịch");
        r.setCampaigns(List.of("a"));
        r.getMetrics().put("clicks", null);
        assertThat(CompanyRules.anomalies(r).getFirst()).contains("Còn thiếu Lượt nhấp");
        assertThat(CompanyRules.missingMetrics(r).stream().map(CompanyRules.MetricDef::key).toList()).containsExactly("clicks");

        assertThat(CompanyRules.validateReportPatch(patch(Map.of("orders", -1), null)).errors().get("orders")).contains("không âm");
        assertThat(CompanyRules.validateReportPatch(patch(Map.of("dso_after", 1.5), null)).errors().get("dso_after")).contains("số nguyên");
        assertThat(CompanyRules.validateReportPatch(patch(Map.of("orders", ""), null)).value().metrics()).containsEntry("orders", null).hasSize(1);
        assertThat(CompanyRules.validateReportPatch(patch(Map.of("orders", "12"), null)).value().metrics()).containsEntry("orders", 12L);

        CompanyReport p = new CompanyReport("y", "t", "2026-10-04", 17, Instant.now());
        p.setMetrics(new LinkedHashMap<>(FULL));
        p.setNotes("n");
        assertThat(Api.JSON.writeValueAsString(CompanyRules.payloadOf(p)))
                .isEqualTo("{\"team_id\":\"t\",\"date\":\"2026-10-04\",\"slot\":17,\"metrics\":{\"spend\":1,\"messages\":2,\"phones\":3,\"orders\":4,\"dso_after\":5,\"impressions\":6,\"clicks\":7},\"notes\":\"n\",\"issue\":\"\",\"resolution\":\"\"}");
    }

    CompanyConfigPatch cfgPatch(java.util.function.Consumer<CompanyConfigPatch> f) {
        CompanyConfigPatch p = new CompanyConfigPatch();
        f.accept(p);
        return p;
    }

    static CompanyConfigPatch.TeamRequest teamReq(String id, String code, List<String> accs, String match) { return new CompanyConfigPatch.TeamRequest(id, code, "", accs, match); }

    @Test
    void configValidation() {
        CompanyConfig withPw = new CompanyConfig(1);
        withPw.setPassword("x");
        withPw.setSlots(List.of(9));
        CompanyConfig noPw = new CompanyConfig(1);
        noPw.setSlots(List.of(9));
        CompanyConfigPatch.TeamRequest t = teamReq("team-1", "CT01", List.of("mock_a"), "");
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> { p.setEnabled(true); p.setEmail("a@b.vn"); p.setTeams(List.of(t)); }), withPw).ok()).isTrue();
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> { p.setEnabled(true); p.setEmail("a@b.vn"); p.setTeams(List.of(t)); }), noPw).errors().get("password")).contains("mật khẩu");
        withPw.setEmail("a@b.vn");
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> { p.setEnabled(true); p.setTeams(List.of()); }), withPw).errors().get("teams")).contains("ít nhất một Team");
        CompanyConfig blank = new CompanyConfig(1);
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> p.setTeams(List.of(teamReq("team-1", "CT01", List.of(), "")))), blank).errors().get("teams")).contains("CT01: hãy chọn tài khoản");
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> p.setTeams(List.of(t, t))), blank).errors().get("teams")).contains("hai lần");
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> p.setBaseUrl("http://mkt.companyos.site")), blank).errors().get("baseUrl")).contains("https");
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> p.setSlots(List.of(8))), blank).errors().get("slots")).contains("không hợp lệ");
        assertThat(CompanyRules.validateConfig(cfgPatch(p -> p.setLeadMin(61.0)), blank).errors().get("leadMin")).contains("0 đến 60");
        Result<CompanyRules.ConfigValue> v = CompanyRules.validateConfig(cfgPatch(p -> { p.setPassword(""); p.setSlots(List.of(22, "9")); p.setLeadMin(15.0);
            p.setTeams(List.of(teamReq("team-1", "CT01", List.of("act_mock_a"), " CT01 ; hoạt huyết "))); }), blank);
        assertThat(v.ok()).isTrue();
        assertThat(v.value().password()).as("mật khẩu trống = giữ mật khẩu cũ").isNull();
        assertThat(v.value().slots()).containsExactly(9, 22);
        assertThat(v.value().leadMin()).isEqualTo(15);
        assertThat(v.value().teams().getFirst().match()).isEqualTo("ct01, hoạt huyết");
        assertThat(v.value().teams().getFirst().accountIds()).containsExactly("mock_a");
    }

    @Test
    void configApiNeverShowsPassword() {
        Api a = new Api(port);
        Api.Res r = a.get("/api/company");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("config").get("has_password").asBoolean()).isTrue();
        assertThat(r.body().get("config").has("password")).isFalse();
        assertThat(r.body().toString()).doesNotContain(CompanyStub.GOOD_LOGIN);
        Api.Res bad = a.post("/api/company/config", Map.of("baseUrl", "http://x.vn"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("errors").get("baseUrl").asString()).contains("https");
        Api.Res ok = a.post("/api/company/config", Map.of("password", "", "leadMin", "5"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("config").get("leadMin").asInt()).isEqualTo(5);
        assertThat(company.config().getPassword()).as("mật khẩu trống = giữ").isEqualTo(CompanyStub.GOOD_LOGIN);
    }

    // ------------------------------------------------------------------ Tạo báo cáo theo mốc (dữ liệu giả)
    static AdObject camp(String id, String acc, String name) {
        return obj(id, acc, name, AdLevel.CAMPAIGN);
    }

    static AdObject adset(String id, String acc, String name) {
        return obj(id, acc, name, AdLevel.ADSET);
    }

    static AdObject obj(String id, String acc, String name, AdLevel level) {
        return AdObject.builder(id, name, level).state("ACTIVE", "ACTIVE").account(acc, null, null).build();
    }

    static Metrics m(double spend, double conv, double leads, double results, double revenue, double imp, double clicks) {
        return new Metrics(spend, imp, 0, clicks, results, results > 0 ? spend / results : null, revenue, null, conv, 0, leads, 0, 0);
    }

    @Test
    void slotCreatesOneDraftPerTeamAndNotifies() {
        atVn("2026-10-05", 9, 3);
        company.tick();
        atVn("2026-10-05", 9, 5);
        company.tick(); // vòng sau trong cùng mốc: không tạo lại
        List<CompanyReport> list = company.list();
        assertThat(list).hasSize(1);
        CompanyReport r = list.getFirst();
        assertThat(r.getDate()).as("mốc 9h sáng 05/10 là báo cáo 9h ngày 04/10").isEqualTo("2026-10-04");
        assertThat(r.getDateRule()).isEqualTo(CompanyRules.DATE_RULE);
        assertThat(r.getSlot()).isEqualTo(9);
        assertThat(r.getStatus()).isEqualTo("pending");
        assertThat(r.getMetrics().get("orders")).isNotNull();
        assertThat(r.getCampaigns()).as("chỉ các camp của tài khoản mẫu A").isNotEmpty().hasSizeLessThanOrEqualTo(4);
        assertThat(r.getMetrics().get("spend")).isPositive();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).contains("Báo cáo công ty · 9h ngày 04/10</b> · chốt cả ngày, cập nhật sáng 05/10").contains("Đơn hàng: <b>")
                .contains("Đang dùng dữ liệu giả");
        assertThat(co.calls).as("tạo báo cáo không gọi hệ thống công ty").isEmpty();
    }

    @Test
    void noDraftOutsideSlotOrDisabled() {
        atVn("2026-10-04", 9, 30);
        company.tick();
        configure(c -> c.setSlots(List.of(12)));
        atVn("2026-10-04", 17, 0);
        company.tick();
        configure(c -> c.setEnabled(false));
        atVn("2026-10-04", 12, 0);
        company.tick();
        assertThat(company.list()).isEmpty();
    }

    @Test
    void leadMinutes() {
        configure(c -> c.setLeadMin(10));
        atVn("2026-10-04", 11, 49);
        company.tick();
        assertThat(company.list()).isEmpty();
        atVn("2026-10-04", 11, 50);
        company.tick();
        assertThat(company.list()).hasSize(1);
        assertThat(company.list().getFirst().getSlot()).isEqualTo(12);
        atVn("2026-10-04", 12, 0);
        company.tick();
        assertThat(company.list()).as("không làm lại lúc 12h").hasSize(1);
    }

    @Test
    void previewModeNeverSends() {
        configure(c -> c.setMode("preview"));
        CompanyReport r = company.createDrafts(17, "2026-10-04", false).getFirst();
        assertThat(tg.texts.getFirst()).contains("Chế độ Chỉ xem");
        company.update(r.getId(), patch(Map.of("orders", 3, "dso_after", 900000), null));
        settings.update(s -> s.setMock(false));
        assertThatThrownBy(() -> company.send(r.getId(), CompanyReportService.SOURCE)).hasMessageContaining("Chỉ xem");
        assertThat(co.calls).isEmpty();
    }

    @Test
    void refreshKeepsManualEdits() {
        CompanyReport r = company.createDrafts(12, "2026-10-04", true).getFirst();
        r = company.update(r.getId(), patch(Map.of("orders", 5, "dso_after", 1000000), "ok"));
        assertThat(r.getEdited()).containsExactlyInAnyOrder("orders", "dso_after");
        r.getMetrics().put("spend", -1L);
        reports.save(r);
        CompanyReport again = company.createDrafts(12, "2026-10-04", true).getFirst();
        assertThat(again.getId()).isEqualTo(r.getId());
        assertThat(again.getMetrics()).containsEntry("orders", 5L).containsEntry("dso_after", 1000000L);
        assertThat(again.getMetrics().get("spend")).as("số chưa sửa được làm mới").isPositive();
        assertThat(again.getNotes()).isEqualTo("ok");
        assertThat(company.list()).hasSize(1);
    }

    // ------------------------------------------------------------------ Gửi lên công ty
    CompanyReport readyDraft() {
        CompanyReport r = company.createDrafts(17, "2026-10-04", true).getFirst();
        r = company.update(r.getId(), patch(Map.of("orders", 4, "dso_after", 2500000), "Ổn"));
        settings.update(s -> s.setMock(false));
        return r;
    }

    @Test
    void sendLogsInChecksThenPosts() {
        CompanyReport r = readyDraft();
        r = company.send(r.getId(), CompanyReportService.SOURCE);
        assertThat(co.calls.stream().map(c -> c.method() + " " + c.path()).toList()).containsExactly("POST /auth/login", "GET /reports", "POST /reports");
        CompanyStub.Call post = co.calls.get(2);
        assertThat(post.headers()).containsEntry("Cookie", "wellday_session=sess1").containsEntry("X-CSRF-Token", "csrf1");
        assertThat(post.body()).containsEntry("team_id", "team-1").containsEntry("date", "2026-10-04").containsEntry("slot", 17).containsEntry("notes", "Ổn")
                .containsEntry("issue", "").containsEntry("resolution", "");
        assertThat(((Map<?, ?>) post.body().get("metrics")).get("orders")).isEqualTo(4);
        assertThat(co.calls.get(1).query()).containsEntry("team_id", "team-1");
        assertThat(r.getStatus()).isEqualTo("sent");
        assertThat(r.getRemoteId()).isEqualTo("rep-1");
        assertThat(r.getRemote().revision()).isEqualTo(1);
        assertThat(r.getRemote().locked()).isFalse();
        LogEntry l = lastLog();
        assertThat(l.getKind()).isEqualTo(LogKind.COMPANY);
        assertThat(l.getOk()).isTrue();
        assertThat(logs.recent(50).stream().map(x -> x.getDetail() + x.getError()).toList().toString()).as("mật khẩu không bao giờ vào nhật ký").doesNotContain(CompanyStub.GOOD_LOGIN);
        String id = r.getId();
        assertThatThrownBy(() -> company.send(id, CompanyReportService.SOURCE)).hasMessageContaining("đã gửi rồi");
        assertThat(co.reports).hasSize(1);
    }

    @Test
    void existingSlotIsNotOverwritten() {
        CompanyReport r = readyDraft();
        co.reports.add(new LinkedHashMap<>(Map.of("id", "old", "team_id", "team-1", "date", "2026-10-04", "slot", 17, "user_id", "u1", "status", "SUBMITTED")));
        assertThatThrownBy(() -> company.send(r.getId(), CompanyReportService.SOURCE)).isInstanceOf(ApiException.class).hasMessageContaining("đã có báo cáo");
        assertThat(reload(r).getStatus()).isEqualTo("exists");
        assertThat(co.posts()).isEmpty();
    }

    @Test
    void missingNumbersOrMockNeverSend() {
        CompanyReport r = company.createDrafts(17, "2026-10-04", true).getFirst();
        assertThatThrownBy(() -> company.send(r.getId(), CompanyReportService.SOURCE)).hasMessageContaining("dữ liệu giả");
        settings.update(s -> s.setMock(false));
        company.update(r.getId(), patch(Map.of("orders", "", "dso_after", ""), null));
        assertThatThrownBy(() -> company.send(r.getId(), CompanyReportService.SOURCE)).hasMessageContaining("Còn thiếu: Số đơn hàng, DSO sau VAT");
        assertThat(co.calls).isEmpty();
    }

    @Test
    void expiredSessionReloginsOnceWrongPasswordFails() {
        CompanyReport r = readyDraft();
        api.login(company.config());
        co.expire = true;
        assertThat(company.send(r.getId(), CompanyReportService.SOURCE).getStatus()).isEqualTo("sent");
        assertThat(co.logins).isEqualTo(2);

        CompanyReport r2 = company.createDrafts(22, "2026-10-04", true).getFirst();
        company.update(r2.getId(), patch(Map.of("orders", 1, "dso_after", 1), null));
        configure(c -> c.setPassword("sai"));
        assertThatThrownBy(() -> company.send(r2.getId(), CompanyReportService.SOURCE)).hasMessageContaining("từ chối đăng nhập");
        assertThat(reload(r2).getStatus()).isEqualTo("failed");
    }

    @Test
    void testConnectionAndTeams() {
        CompanyLogin me = company.test();
        assertThat(me.user().get("name")).isEqualTo("Nguyễn Minh Phương");
        assertThat(api.listTeams(company.config()).getFirst()).isEqualTo(new CompanyApi.Team("team-1", "CT01", "WDC - Hoạt Huyết", "ACTIVE"));
    }

    // ------------------------------------------------------------------ Tự động gửi (số Facebook cố định)
    /** CompanyDrafts (chỉ dùng trong gói company) là nơi lấy số Facebook */
    Object drafts() {
        return ReflectionTestUtils.getField((Object) AopTestUtils.getTargetObject(company), "drafts");
    }

    void stubFb(Metrics c1) {
        if (objects == null) {
            objects = Mockito.mock(FacebookObjects.class);
            insights = Mockito.mock(FacebookInsights.class);
        }
        ReflectionTestUtils.setField(drafts(), "objects", objects);
        ReflectionTestUtils.setField(drafts(), "insights", insights);
        doReturn(List.of(camp("c1", "mock_a", "CT01 Hoạt huyết"), camp("c2", "mock_b", "Khác"))).when(objects).listObjects(anyBoolean());
        doReturn(Map.of("c1", c1, "c2", m(999, 9, 9, 9, 9, 9, 9))).when(insights).rangeMetrics(anyString(), anyBoolean());
    }

    static final Metrics FB = m(1520000, 15, 12, 7, 2660000, 13680000, 1689);

    void autoMode() {
        stubFb(FB);
        configure(c -> c.setMode("auto"));
        settings.update(s -> s.setMock(false));
    }

    @Test
    void autoSendsSevenNumbersOnce() {
        autoMode();
        atVn("2026-10-04", 17, 1);
        company.tick();
        atVn("2026-10-04", 17, 2);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getStatus()).isEqualTo("sent");
        assertThat(co.posts()).hasSize(1);
        assertThat(co.posts().getFirst().body().get("metrics").toString())
                .isEqualTo("{spend=1520000, messages=15, phones=12, orders=7, dso_after=2660000, impressions=13680000, clicks=1689}");
        assertThat(co.posts().getFirst().body().get("slot")).isEqualTo(17);
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).startsWith("✅ <b>Báo cáo công ty · 17h ngày 04/10").contains("Đã tự gửi lên công ty");
        assertThat(lastLog().getSource()).isEqualTo(CompanyReportService.AUTO_SOURCE);
        assertThat(lastLog().getOk()).isTrue();
    }

    @Test
    void autoStopsOnOrdersWithoutRevenueThenManualSend() {
        autoMode();
        stubFb(m(100000, 2, 1, 3, 0, 1000, 10));
        atVn("2026-10-04", 12, 0);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getStatus()).isEqualTo("review");
        assertThat(r.getReasons().getFirst()).contains("doanh thu bằng 0");
        assertThat(co.calls).as("không gọi hệ thống công ty").isEmpty();
        assertThat(tg.texts.getFirst()).contains("Chưa tự gửi vì số trông bất thường");
        company.update(r.getId(), patch(Map.of("dso_after", 900000), null));
        assertThat(company.send(r.getId(), CompanyReportService.SOURCE).getStatus()).isEqualTo("sent");
        assertThat(((Map<?, ?>) co.posts().getFirst().body().get("metrics")).get("dso_after")).isEqualTo(900000);
    }

    @Test
    void autoStopsWhenTeamMatchesNothing() {
        autoMode();
        configure(c -> c.setTeams(List.of(new CompanyConfig.Team("team-1", "CT01", "x", List.of("khong_co"), ""))));
        atVn("2026-10-04", 22, 0);
        company.tick();
        assertThat(company.list().getFirst().getStatus()).isEqualTo("review");
        assertThat(co.calls).isEmpty();
    }

    @Test
    void autoReportsFacebookFailure() {
        autoMode();
        doThrow(new RuntimeException("Token Facebook đã hết hạn")).when(objects).listObjects(anyBoolean());
        atVn("2026-10-05", 9, 0);
        company.tick();
        assertThat(company.list()).isEmpty();
        assertThat(co.calls).isEmpty();
        assertThat(tg.texts.getFirst()).contains("không lấy được số Facebook");
    }

    @Test
    void autoRetries5xxThreeTimes() {
        autoMode();
        co.fail5xx = true;
        atVn("2026-10-04", 17, 0);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getStatus()).isEqualTo("retry");
        assertThat(r.getAttempts()).isEqualTo(1);
        assertThat(tg.texts).as("lần đầu lỗi chưa làm phiền").isEmpty();
        atVn("2026-10-04", 17, 1);
        company.tick(); // chưa đến giờ thử lại
        assertThat(co.posts()).hasSize(1);
        for (int n : List.of(2, 3)) {
            atVn("2026-10-04", 17, 11 * (n - 1));
            company.tick();
            assertThat(reload(r).getAttempts()).isEqualTo(n);
        }
        assertThat(reload(r).getStatus()).isEqualTo("failed");
        assertThat(co.posts()).hasSize(3);
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).contains("Không tự gửi được (đã thử 3 lần)");
        atVn("2026-10-04", 18, 0);
        company.tick();
        assertThat(co.posts()).as("thất bại rồi thì không thử nữa").hasSize(3);
    }

    @Test
    void autoWrongPasswordFailsAtOnce() {
        autoMode();
        configure(c -> c.setPassword("sai"));
        atVn("2026-10-04", 17, 0);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getStatus()).isEqualTo("failed");
        assertThat(r.getAttempts()).isEqualTo(1);
        assertThat(co.logins).isEqualTo(1);
        assertThat(tg.texts.getFirst()).containsPattern("Không tự gửi được: .*từ chối đăng nhập");
    }

    @Test
    void autoDoesNotOverwriteExisting() {
        autoMode();
        co.reports.add(new LinkedHashMap<>(Map.of("id", "old", "team_id", "team-1", "date", "2026-10-04", "slot", 17, "user_id", "u1", "status", "SUBMITTED")));
        atVn("2026-10-04", 17, 0);
        company.tick();
        assertThat(company.list().getFirst().getStatus()).isEqualTo("exists");
        assertThat(co.posts()).isEmpty();
        assertThat(tg.texts.getFirst()).contains("không gửi đè");
    }

    Map<String, Object> yesterday9h(boolean locked) {
        Map<String, Object> r = new LinkedHashMap<>(Map.of("id", "rep-9h", "team_id", "team-1", "date", "2026-10-04", "slot", 9, "user_id", "u1", "status", "SUBMITTED",
                "revision", 1, "locked", locked));
        r.put("metrics", new LinkedHashMap<>(Map.of("spend", 1, "messages", 1, "phones", 1, "orders", 1, "dso_after", 1, "impressions", 1, "clicks", 1)));
        return r;
    }

    @Test
    void auto9hUpdatesYesterdaysRecord() {
        autoMode();
        co.reports.add(yesterday9h(false));
        atVn("2026-10-05", 9, 1);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getDate()).isEqualTo("2026-10-04");
        assertThat(r.getStatus()).isEqualTo("sent");
        assertThat(r.getSentAs()).isEqualTo("update");
        assertThat(co.posts()).hasSize(1);
        assertThat(co.updates).hasSize(1);
        assertThat(co.updates.getFirst()).containsEntry("revision", 1).containsEntry("reason", "Chốt số liệu cả ngày 04/10").containsEntry("date", "2026-10-04");
        assertThat(co.reports).as("không tạo báo cáo ngày 05/10").hasSize(1);
        assertThat(((Map<?, ?>) co.reports.getFirst().get("metrics")).get("orders")).isEqualTo(7);
        assertThat(r.getRemote().revision()).isEqualTo(2);
        assertThat(tg.texts.getFirst()).contains("9h ngày 04/10</b> · chốt cả ngày, cập nhật sáng 05/10").contains("Đã tự cập nhật vào báo cáo 9h ngày 04/10 trên công ty");
        assertThat(lastLog().getDetail()).containsPattern("Đã cập nhật báo cáo 9h ngày 04/10 .*Chốt số liệu cả ngày 04/10");
    }

    @Test
    void auto9hCreatesWhenMissing() {
        autoMode();
        atVn("2026-10-05", 9, 1);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getStatus()).isEqualTo("sent");
        assertThat(r.getSentAs()).isEqualTo("create");
        assertThat(co.posts()).hasSize(1);
        assertThat(co.posts().getFirst().body()).containsEntry("date", "2026-10-04").doesNotContainKey("reason");
        assertThat(co.updates).isEmpty();
        assertThat(tg.texts.getFirst()).contains("Đã tự gửi lên công ty");
    }

    @Test
    void auto9hLeavesLockedRecord() {
        autoMode();
        co.reports.add(yesterday9h(true));
        atVn("2026-10-05", 9, 1);
        company.tick();
        CompanyReport r = company.list().getFirst();
        assertThat(r.getStatus()).isEqualTo("exists");
        assertThat(co.posts()).isEmpty();
        assertThat(((Map<?, ?>) co.reports.getFirst().get("metrics")).get("orders")).isEqualTo(1);
        assertThat(tg.texts.getFirst()).contains("đã khoá nên tool không cập nhật được");
    }

    @Test
    void approve9hSyncKeepsPendingThenSendUpdates() {
        stubFb(FB);
        settings.update(s -> s.setMock(false));
        CompanyReport r = company.createDrafts(9, "2026-10-05", true).getFirst();
        co.reports.add(yesterday9h(false));
        assertThat(company.sync()).isEqualTo(1);
        assertThat(reload(r).getStatus()).as("mốc 9h: bản ghi đã có là chỗ sẽ cập nhật vào").isEqualTo("pending");
        assertThat(company.send(r.getId(), CompanyReportService.SOURCE).getSentAs()).isEqualTo("update");
        assertThat(co.updates).hasSize(1);
        assertThat(co.updates.getFirst()).containsEntry("reason", CompanyRules.closeReason(r.getDate()));
    }

    @Test
    void autoInMockModeOnlyNotifies() {
        autoMode();
        settings.update(s -> s.setMock(true));
        atVn("2026-10-04", 17, 0);
        company.tick();
        assertThat(company.list().getFirst().getStatus()).isEqualTo("pending");
        assertThat(co.calls).isEmpty();
        assertThat(tg.texts.getFirst()).contains("dữ liệu giả");
    }

    // ------------------------------------------------------------------ Cập nhật báo cáo đã có + đồng bộ
    @Test
    void updateRemoteNeedsReasonAndLocksAfter() {
        CompanyReport r = company.send(readyDraft().getId(), CompanyReportService.SOURCE);
        assertThat(r.getRemote().revision()).isEqualTo(1);
        company.update(r.getId(), patch(Map.of("orders", 6), null));
        String id = r.getId();
        assertThatThrownBy(() -> company.updateRemote(id, "  ", CompanyReportService.SOURCE)).hasMessageContaining("lý do");
        r = company.updateRemote(id, "Chốt thêm 2 đơn", CompanyReportService.SOURCE);
        assertThat(co.updates).hasSize(1);
        assertThat(co.updates.getFirst()).containsEntry("revision", 1).containsEntry("reason", "Chốt thêm 2 đơn");
        assertThat(((Map<?, ?>) co.updates.getFirst().get("metrics")).get("orders")).isEqualTo(6);
        assertThat(r.getRemote().revision()).isEqualTo(2);
        assertThat(r.getRemote().locked()).as("công ty khoá lại sau một lần sửa").isTrue();
        assertThat(lastLog().getDetail()).containsPattern("Đã cập nhật báo cáo 17h.*Chốt thêm 2 đơn");
        // đã khoá → không sửa nữa
        assertThatThrownBy(() -> company.update(id, patch(Map.of("orders", 7), null))).hasMessageContaining("đã khoá");
        int before = co.calls.size();
        assertThatThrownBy(() -> company.updateRemote(id, "x", CompanyReportService.SOURCE)).hasMessageContaining("đã khoá");
        assertThat(co.calls.subList(before, co.calls.size())).allMatch(c -> c.method().equals("GET"));
    }

    @Test
    void updateRemoteRefusesWhenEditedElsewhere() {
        CompanyReport r = company.send(readyDraft().getId(), CompanyReportService.SOURCE);
        Map<String, Object> remote = co.reports.getFirst();
        remote.put("revision", 2);
        ((Map<String, Object>) remote.get("metrics")).put("orders", 9);
        company.update(r.getId(), patch(Map.of("orders", 5), null));
        String id = r.getId();
        assertThatThrownBy(() -> company.updateRemote(id, "Sửa đơn", CompanyReportService.SOURCE)).hasMessageContaining("vừa được sửa ở nơi khác (lần sửa 2)");
        assertThat(co.updates).isEmpty();
        assertThat(reload(r).getRemote().revision()).isEqualTo(2);
        assertThat(reload(r).getRemote().metrics()).containsEntry("orders", 9L);
        company.updateRemote(id, "Sửa đơn", CompanyReportService.SOURCE);
        assertThat(co.updates.getFirst()).containsEntry("revision", 2);
    }

    @Test
    void syncMarksManualWebReports() {
        CompanyReport r = readyDraft();
        Map<String, Object> web = new LinkedHashMap<>(Map.of("id", "web-1", "team_id", "team-1", "date", "2026-10-04", "slot", 17, "user_id", "u1", "status", "LATE",
                "revision", 1, "locked", false));
        web.put("metrics", new LinkedHashMap<>(Map.of("spend", 1, "messages", 1, "phones", 1, "orders", 1, "dso_after", 1, "impressions", 1, "clicks", 1)));
        co.reports.add(web);
        assertThat(company.sync()).isEqualTo(1);
        CompanyReport x = reload(r);
        assertThat(x.getStatus()).isEqualTo("exists");
        assertThat(x.getRemote().status()).isEqualTo("LATE");
        assertThat(x.getRemote().metrics()).containsEntry("orders", 1L);
        configure(c -> c.setMode("preview"));
        assertThatThrownBy(() -> company.updateRemote(r.getId(), "x", CompanyReportService.SOURCE)).hasMessageContaining("Chỉ xem");
        configure(c -> c.setMode("approve"));
        settings.update(s -> s.setMock(true));
        assertThatThrownBy(() -> company.updateRemote(r.getId(), "x", CompanyReportService.SOURCE)).hasMessageContaining("dữ liệu giả");
        assertThat(company.sync()).as("Dùng thử không gọi công ty").isZero();
        assertThat(co.updates).isEmpty();
    }

    @Test
    void reportsApi() {
        Api a = new Api(port);
        Api.Res built = a.post("/api/company/reports/build", Map.of("slot", 12));
        assertThat(built.status()).isEqualTo(200);
        String id = built.body().get("built").get(0).asString();
        assertThat(built.body().get("reports").get(0).get("date").asString()).isEqualTo("2026-10-04");
        assertThat(a.post("/api/company/reports/build", Map.of("slot", 8)).status()).isEqualTo(400);
        Api.Res edited = a.post("/api/company/reports/" + id, Map.of("metrics", Map.of("orders", "abc")));
        assertThat(edited.status()).isEqualTo(400);
        assertThat(edited.body().get("errors").get("orders").asString()).contains("số nguyên");
        assertThat(a.post("/api/company/reports/" + id, Map.of("metrics", Map.of("orders", "3"), "notes", "x")).body().get("metrics").get("orders").asInt()).isEqualTo(3);
        assertThat(a.post("/api/company/reports/" + id + "/send", Map.of()).body().get("error").asString()).contains("dữ liệu giả");
        assertThat(a.delete("/api/company/reports/" + id).status()).isEqualTo(200);
        assertThat(company.list()).isEmpty();
    }
}
