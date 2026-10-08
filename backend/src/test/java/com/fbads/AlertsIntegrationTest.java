package com.fbads;

import com.fbads.client.GraphClient;
import com.fbads.engine.AlertWatch;
import com.fbads.engine.EngineClock;
import com.fbads.notify.channel.TelegramChannel;
import com.fbads.repository.NotifyTargetRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.WsState;
import com.fbads.service.facebook.FacebookGraph;
import com.fbads.service.facebook.FacebookInsights.HourSpend;
import com.fbads.service.facebook.FacebookState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Cảnh báo bất thường: tài khoản có vấn đề, quảng cáo bị từ chối, chi tiêu tăng vọt (bản Node: tests/alerts). Facebook và Telegram đều là máy chủ giả. */
class AlertsIntegrationTest extends IntegrationBase {
    @Autowired SettingsService settings;
    @Autowired FacebookGraph fbGraph;
    @Autowired FacebookState fbState;
    @Autowired TelegramChannel telegram;
    @Autowired NotifyTargetRepository notifyTargets;
    @Autowired AlertWatch alerts;
    @Autowired EngineClock clock;
    @Autowired WsState state;
    @Autowired LogService logs;
    @Autowired com.fbads.client.RateLimits limits;

    GraphStub fbStub;
    TelegramStub tg;
    WorkspaceContext.Scope ws;
    Instant now = Instant.parse("2026-09-28T08:00:00Z");

    // Dữ liệu Facebook giả, đổi được trong từng test
    volatile int status = 1;
    volatile String adsJson = "[]";
    volatile double[] today = {50000, 50000, 50000}, yesterday = {50000, 50000, 50000, 50000};

    static String hours(double[] arr) {
        List<String> rows = new ArrayList<>();
        for (int h = 0; h < arr.length; h++)
            rows.add(String.format("{\"hourly_stats_aggregated_by_advertiser_time_zone\":\"%02d:00:00 - %02d:59:59\",\"spend\":\"%s\"}", h, h, (long) arr[h]));
        return "[" + String.join(",", rows) + "]";
    }

    List<String> paths() { return fbStub.calls.stream().map(GraphStub.Req::path).toList(); }

    void tick(Duration later) {
        now = now.plus(later);
        clock.setClock(Clock.fixed(now, ZoneOffset.UTC));
        alerts.tick();
    }

    @BeforeEach
    void setUp() throws Exception {
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        fbStub = new GraphStub();
        tg = new TelegramStub();
        fbStub.handler = r -> {
            if (r.path().endsWith("/ads")) return GraphStub.Res.ok("{\"data\":" + adsJson + "}");
            if (r.path().endsWith("/insights")) return GraphStub.Res.ok("{\"data\":" + hours("today".equals(r.params().get("date_preset")) ? today : yesterday) + "}");
            return GraphStub.Res.ok("{\"name\":\"Shop A\",\"currency\":\"VND\",\"account_status\":" + status + "}");
        };
        fbGraph.setGraphBase(fbStub.base());
        telegram.setApiBase(tg.base());
        TestChannels.telegram(notifyTargets, "1:x", "42");
        clock.setClock(Clock.fixed(now, ZoneOffset.UTC));
        settings.update(s -> { s.setMock(false); s.setAccessToken("EAAtoken-1234567890"); s.setAdAccountIds(List.of("111")); s.setAdAccountId("111");
            s.setAlertAccount(true); s.setAlertDisapproved(true); s.setAlertSpike(true); s.setSpikePct(50); s.setSpikeMinSpend(100000); });
        fbState.resetCache();
        alerts.reset();
        state.clearAll();
    }

    @AfterEach
    void tearDown() {
        settings.update(s -> { s.setMock(true); s.setAccessToken(""); s.setAdAccountIds(List.of()); s.setAdAccountId("");
            s.setAlertAccount(true); s.setAlertDisapproved(true); s.setAlertSpike(true); });
        fbState.resetCache();
        limits.reset();
        fbGraph.setGraphBase(GraphClient.BASE);
        telegram.setApiBase("https://api.telegram.org");
        TestChannels.clear(notifyTargets);
        clock.setClock(Clock.systemUTC());
        alerts.reset();
        state.clearAll();
        fbStub.close();
        tg.close();
        ws.close();
    }

    @Test
    void quietWhenNormalAndChecksEvery30Minutes() {
        alerts.tick();
        assertThat(tg.texts).isEmpty();
        int n = fbStub.calls.size();
        assertThat(n).isPositive();
        tick(Duration.ofMinutes(10));
        assertThat(fbStub.calls).as("chưa đủ 30 phút: không gọi Facebook").hasSize(n);
        tick(Duration.ofMinutes(25));
        assertThat(fbStub.calls.size()).isGreaterThan(n);
    }

    @Test
    void accountDisabledThenActiveAgain() {
        status = 2;
        alerts.tick();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).contains("Tài khoản quảng cáo có vấn đề").contains("Bị vô hiệu hoá");
        alerts.reset();
        tick(Duration.ofMinutes(31));
        assertThat(tg.texts).as("cùng trạng thái: không báo lại").hasSize(1);
        status = 1;
        tick(Duration.ofMinutes(31));
        assertThat(tg.texts).hasSize(2);
        assertThat(tg.texts.get(1)).contains("đã hoạt động lại");
        assertThat(logs.recent(1).getFirst().getSource()).isEqualTo("Cảnh báo");
    }

    @Test
    void disapprovedAdsReportedOnce() {
        adsJson = "[{\"id\":\"ad1\",\"name\":\"Video <sale>\",\"campaign\":{\"name\":\"Camp 1\"},\"adset\":{\"name\":\"Nhóm 1\"},\"ad_review_feedback\":{\"global\":{\"ADULT\":\"Nội dung người lớn\"}}}]";
        alerts.tick();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).contains("1 quảng cáo bị từ chối").as("tên được thoát ký tự HTML").contains("Video &lt;sale&gt;").contains("Nội dung người lớn");
        assertThat(fbStub.calls.stream().filter(r -> r.path().endsWith("/ads")).findFirst().orElseThrow().params().get("effective_status")).isEqualTo("[\"DISAPPROVED\"]");
        adsJson = adsJson.substring(0, adsJson.length() - 1) + ",{\"id\":\"ad2\",\"name\":\"Ảnh 2\",\"campaign\":{\"name\":\"Camp 1\"}}]";
        tick(Duration.ofMinutes(31));
        assertThat(tg.texts).hasSize(2);
        assertThat(tg.texts.get(1)).contains("1 quảng cáo bị từ chối").contains("Ảnh 2").doesNotContain("Video");
    }

    @Test
    void spendSpikeOncePerDay() {
        today = new double[]{100000, 100000, 100000};            // tới 03:00 đã chi 300k
        yesterday = new double[]{50000, 50000, 50000, 900000};    // cùng giờ hôm qua (0–2h) chi 150k → tăng 100%
        alerts.tick();
        assertThat(tg.texts).hasSize(1);
        assertThat(tg.texts.getFirst()).contains("Chi tiêu tăng vọt").contains("cao hơn 100%").contains("Hôm nay tới 03:00: <b>300.000 VND</b>");
        tick(Duration.ofMinutes(31));
        assertThat(tg.texts).as("đã báo hôm nay").hasSize(1);
        tick(Duration.ofDays(1));
        assertThat(tg.texts).as("ngày mới báo lại").hasSize(2);
    }

    @Test
    void spikeOfRules() {
        assertThat(AlertWatch.spikeOf(List.of(new HourSpend(0, 90000)), List.of(new HourSpend(0, 10000)), 50, 100000)).as("chưa chi đủ mức tối thiểu").isNull();
        assertThat(AlertWatch.spikeOf(List.of(new HourSpend(0, 300000)), List.of(), 50, 100000)).as("hôm qua chưa chi").isNull();
        assertThat(AlertWatch.spikeOf(List.of(new HourSpend(0, 140000)), List.of(new HourSpend(0, 100000)), 50, 100000)).as("tăng 40% < 50%").isNull();
        assertThat(AlertWatch.spikeOf(List.of(new HourSpend(1, 200000)), List.of(new HourSpend(0, 50000), new HourSpend(1, 50000), new HourSpend(2, 500000)), 50, 100000))
                .isEqualTo(new AlertWatch.Spike(1, 200000, 100000, 100));
    }

    @Test
    void disabledKindsAreNotFetched() {
        settings.update(s -> { s.setAlertDisapproved(false); s.setAlertSpike(false); });
        alerts.tick();
        assertThat(paths()).noneMatch(p -> p.endsWith("/ads") || p.endsWith("/insights"));
        settings.update(s -> s.setAlertAccount(false));
        fbStub.calls.clear();
        tick(Duration.ofMinutes(31));
        assertThat(fbStub.calls).as("tắt cả 3 loại: không gọi gì").isEmpty();
    }

    @Test
    void mockModeSkips() {
        settings.update(s -> s.setMock(true));
        alerts.tick();
        assertThat(fbStub.calls).isEmpty();
    }
}
