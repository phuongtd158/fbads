package com.fbads.facebook;

import com.fbads.ads.Metrics;
import com.fbads.facebook.GraphData.Account;
import com.fbads.facebook.GraphData.Ad;
import com.fbads.facebook.GraphData.AdSet;
import com.fbads.facebook.GraphData.Campaign;
import com.fbads.facebook.GraphData.InsightRow;
import com.fbads.facebook.GraphData.TokenDebug;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Đọc JSON mẫu của Facebook vào các record trong GraphData, như bản Node đọc */
class GraphDataTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void campaignAndAdSet() {
        Campaign c = mapper.readValue("""
                {"id":"1","name":"Camp A","status":"ACTIVE","effective_status":"ACTIVE","daily_budget":"50000","extra":1}""",
                Campaign.class);
        assertThat(c.name()).isEqualTo("Camp A");
        assertThat(FacebookParse.budget(c.dailyBudget(), "VND")).isEqualTo(50000.0);
        assertThat(FacebookParse.budget(c.dailyBudget(), "USD")).isEqualTo(500.0);

        Campaign cbo = mapper.readValue("{\"id\":\"2\"}", Campaign.class);
        assertThat(cbo.name()).isEmpty();
        assertThat(cbo.status()).isEmpty();
        assertThat(FacebookParse.budget(cbo.dailyBudget(), "VND")).isNull();

        AdSet a = mapper.readValue("""
                {"id":"3","campaign_id":"1","start_time":"2026-09-01T10:00:00+0700",
                 "learning_stage_info":{"status":"LEARNING"}}""", AdSet.class);
        assertThat(a.isLearning()).isTrue();
        assertThat(FacebookParse.ms(a.startTime())).isEqualTo(1788231600000L);
        assertThat(FacebookParse.ms(a.endTime())).isNull();
        assertThat(mapper.readValue("{\"id\":\"4\"}", AdSet.class).isLearning()).isFalse();
    }

    @Test
    void insightRowToMetrics() {
        InsightRow r = mapper.readValue("""
                {"campaign_id":"1","spend":"100000","impressions":"2000","reach":"1500","clicks":"40",
                 "actions":[{"action_type":"lead","value":"4"},{"action_type":"comment","value":"2"}],
                 "action_values":[{"action_type":"lead","value":"300000"}]}""", InsightRow.class);
        Metrics m = FacebookParse.metricsFrom(r, "lead");
        assertThat(m.spend()).isEqualTo(100000);
        assertThat(m.impressions()).isEqualTo(2000);
        assertThat(m.results()).isEqualTo(4);
        assertThat(m.cpa()).isEqualTo(25000.0);
        assertThat(m.roas()).isEqualTo(3.0);
        assertThat(m.comments()).isEqualTo(2);

        Metrics empty = FacebookParse.metricsFrom(mapper.readValue("{\"adset_id\":\"9\"}", InsightRow.class), "lead");
        assertThat(empty.spend()).isZero();
        assertThat(empty.cpa()).isNull();
    }

    @Test
    void accountAdAndToken() {
        Account acc = mapper.readValue("{\"account_id\":\"7\",\"name\":\"TK\",\"account_status\":1}", Account.class);
        assertThat(acc.status()).isEqualTo(1);
        assertThat(acc.currency()).isEmpty();
        assertThat(mapper.readValue("{}", Account.class).status()).isZero();

        Ad ad = mapper.readValue("""
                {"id":"5","name":"QC","campaign":{"name":"C"},"ad_review_feedback":{"global":{"A":"Lý do 1","B":""}}}""",
                Ad.class);
        assertThat(ad.campaignName()).isEqualTo("C");
        assertThat(ad.adsetName()).isEmpty();
        assertThat(ad.reasons()).containsExactly("Lý do 1");

        TokenDebug t = mapper.readValue("{\"data\":{\"scopes\":[\"ads_read\"],\"expires_at\":100}}", TokenDebug.class);
        assertThat(t.data().valid()).isTrue();
        assertThat(t.data().expiresAtMs()).isEqualTo(100_000L);
        assertThat(mapper.readValue("{\"data\":{\"is_valid\":false}}", TokenDebug.class).data().valid()).isFalse();
    }
}
