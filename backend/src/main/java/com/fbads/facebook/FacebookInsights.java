package com.fbads.facebook;

import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;
import com.fbads.ads.Trend.TrendDay;
import com.fbads.common.ApiException;
import com.fbads.facebook.FacebookState.RangeEntry;
import com.fbads.facebook.FacebookState.TrendEntry;
import com.fbads.facebook.FacebookState.Ws;
import com.fbads.facebook.GraphData.InsightRow;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fbads.facebook.FacebookGraph.actOf;
import static com.fbads.facebook.FacebookParse.INSIGHT_FIELDS;
import static com.fbads.facebook.FacebookParse.metricsFrom;
import static com.fbads.facebook.FacebookParse.parseNum;
import static com.fbads.facebook.FacebookState.FORCE_MIN_MS;
import static com.fbads.facebook.FacebookState.RANGE_TTL_MS;
import static com.fbads.facebook.FacebookState.TTL_BUSY_MS;

/** Số liệu ngoài "hôm nay": theo khoảng ngày (bảng, rule, báo cáo), xu hướng từng ngày (biểu đồ), chi tiêu theo giờ. */
@Service
public class FacebookInsights {
    static final long TREND_TTL_MS = 3_600_000;
    private static final Map<String, Integer> ENGINE_DAYS = Map.of("yesterday", 1, "last_3d", 3, "last_7d", 7);

    /** Khoảng thời gian: key = khoá bộ nhớ đệm, fbParams = date_preset / time_range, days = số ngày (cho dữ liệu giả) */
    public record RangeQuery(String key, Map<String, String> fbParams, Integer days) {}

    public record RangeResult(Map<String, Metrics> data, Long at, boolean stale) {}

    public record TrendResult(List<TrendDay> days, long at, boolean stale) {}

    public record HourSpend(int hour, double spend) {}

    private final SettingsService settings;
    private final RateLimits limits;
    private final FacebookGraph graph;
    private final FacebookState state;
    private final FacebookObjects objects;

    public FacebookInsights(SettingsService settings, RateLimits limits, FacebookGraph graph, FacebookState state,
            FacebookObjects objects) {
        this.settings = settings;
        this.limits = limits;
        this.graph = graph;
        this.state = state;
        this.objects = objects;
    }

    // ------------------------------------------------------------------ Khoảng thời gian

    /** Số liệu theo khoảng: { [id]: metrics }. 'p:today' dùng lại danh sách camp; khoảng khác có bộ nhớ đệm riêng. */
    public RangeResult rangeData(RangeQuery q, boolean force) {
        Ws w = state.ws();
        if ("p:today".equals(q.key())) {
            List<AdObject> list = objects.listObjects(force);
            Map<String, Metrics> m = new LinkedHashMap<>();
            for (AdObject o : list) m.put(o.id(), o.metrics());
            return new RangeResult(m, w.cache.at(), w.cache.stale());
        }
        w.lock.lock();
        try {
            long now = System.currentTimeMillis();
            boolean isMock = state.isMock();
            String l2Key = state.sourceKey() + "|" + q.key();
            RangeEntry c = w.rangeCache.get(q.key());
            if (c == null) { // vừa khởi động: lấy bản đã lưu ở Redis
                FbSnapshots.Range saved = FacebookState.get(state.rangesL2, l2Key, FbSnapshots.Range.class);
                if (saved != null && saved.data() != null) {
                    w.rangeCache.put(q.key(), c = new RangeEntry(saved.at(), saved.data(), saved.mock()));
                }
            }
            long ttl = force ? FORCE_MIN_MS : limits.pct() >= 60 ? TTL_BUSY_MS : RANGE_TTL_MS;
            if (c != null && c.mock == isMock && now - c.at < ttl) return new RangeResult(c.data, c.at, c.stale);
            boolean usable = c != null && c.mock == isMock;
            if (!isMock && limits.blocked()) {
                if (usable) {
                    c.stale = true;
                    return new RangeResult(c.data, c.at, true);
                }
                throw graph.rateLimitError();
            }
            Map<String, Metrics> data;
            try {
                data = isMock ? w.mock.range(q.days() == null ? 90 : q.days()) : fetchRange(q.fbParams());
            } catch (FbException e) {
                if (usable && e.isRateLimit()) {
                    c.stale = true;
                    return new RangeResult(c.data, c.at, true);
                }
                throw e;
            }
            RangeEntry e = new RangeEntry(System.currentTimeMillis(), data, isMock);
            w.rangeCache.put(q.key(), e);
            FacebookState.put(state.rangesL2, l2Key, new FbSnapshots.Range(e.at, data, isMock));
            return new RangeResult(data, e.at, false);
        } finally {
            w.lock.unlock();
        }
    }

    /** Cho rule: 'today' | 'yesterday' | 'last_3d' | 'last_7d' → { [id]: metrics } */
    public Map<String, Metrics> rangeMetrics(String range, boolean force) {
        if (range == null || range.equals("today")) return rangeData(new RangeQuery("p:today", null, null), force).data();
        RangeQuery q = new RangeQuery("p:" + range, Map.of("date_preset", range), ENGINE_DAYS.getOrDefault(range, 1));
        return rangeData(q, force).data();
    }

    private Map<String, Metrics> fetchRange(Map<String, String> fbParams) {
        String rs = settings.get().getResultAction();
        Map<String, Metrics> data = new LinkedHashMap<>();
        for (String id : settings.get().accountIds()) {
            try {
                Map<String, String> pc = new LinkedHashMap<>(Map.of("level", "campaign", "fields",
                        "campaign_id," + INSIGHT_FIELDS));
                pc.putAll(fbParams);
                Map<String, String> pa = new LinkedHashMap<>(Map.of("level", "adset", "fields",
                        "adset_id," + INSIGHT_FIELDS));
                pa.putAll(fbParams);
                for (InsightRow r : graph.getAll(actOf(id) + "/insights", pc, null, InsightRow.class)) {
                    data.put(r.campaignId(), metricsFrom(r, rs));
                }
                for (InsightRow r : graph.getAll(actOf(id) + "/insights", pa, null, InsightRow.class)) {
                    data.put(r.adsetId(), metricsFrom(r, rs));
                }
            } catch (FbException e) {
                if (e.isRateLimit()) throw e; // tài khoản lỗi khác: bỏ qua (đã báo ở danh sách camp)
            }
        }
        return data;
    }

    // ------------------------------------------------------------------ Xu hướng theo ngày (biểu đồ trên Tổng quan)

    /**
     * Số liệu từng ngày của một camp/nhóm QC từ since tới until (YYYY-MM-DD, theo múi giờ tài khoản). Ngày không chạy
     * (Facebook không trả dòng nào) được điền số 0. Giữ trong bộ nhớ 1 giờ, bấm Làm mới thì hỏi lại (vẫn dùng lại
     * nếu vừa tải trong 15 giây). Bị giới hạn số lần gọi thì dùng số cũ nếu có.
     */
    public TrendResult dailyTrend(String id, String since, String until, boolean force) {
        Ws w = state.ws();
        boolean mock = state.isMock();
        String key = (mock ? "m" : "r") + ":" + id + ":" + since + ":" + until;
        TrendEntry c = w.trendCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at < (force ? FORCE_MIN_MS : TREND_TTL_MS)) return new TrendResult(c.days, c.at, c.stale);
        List<String> dates = new ArrayList<>();
        LocalDate end = LocalDate.parse(until);
        for (LocalDate d = LocalDate.parse(since); !d.isAfter(end); d = d.plusDays(1)) dates.add(d.toString());
        List<TrendDay> days = new ArrayList<>();
        if (mock) {
            List<Map.Entry<String, Metrics>> rows = w.mock.trend(id, dates);
            if (rows == null) throw new ApiException(404, "Không tìm thấy camp hoặc nhóm quảng cáo này.");
            for (Map.Entry<String, Metrics> r : rows) days.add(new TrendDay(r.getKey(), r.getValue()));
        } else {
            if (limits.blocked()) {
                if (c != null) {
                    c.stale = true;
                    return new TrendResult(c.days, c.at, true);
                }
                throw graph.rateLimitError();
            }
            List<InsightRow> rows;
            try {
                rows = graph.getAll(id + "/insights", Map.of("time_range",
                        "{\"since\":\"" + since + "\",\"until\":\"" + until + "\"}", "time_increment", "1",
                        "fields", INSIGHT_FIELDS), null, InsightRow.class);
            } catch (FbException e) {
                if (c != null && e.isRateLimit()) {
                    c.stale = true;
                    return new TrendResult(c.days, c.at, true);
                }
                throw e;
            }
            String rs = settings.get().getResultAction();
            Map<String, Metrics> byDate = new LinkedHashMap<>();
            for (InsightRow r : rows) byDate.put(r.dateStart(), metricsFrom(r, rs));
            for (String d : dates) days.add(new TrendDay(d, byDate.getOrDefault(d, Metrics.EMPTY)));
        }
        TrendEntry e = new TrendEntry(System.currentTimeMillis(), days);
        w.trendCache.put(key, e);
        return new TrendResult(days, e.at, false);
    }

    // ------------------------------------------------------------------ Chi tiêu theo giờ (cảnh báo bất thường)

    /** Chi tiêu theo từng giờ của cả tài khoản (giờ theo múi giờ của tài khoản), preset = today | yesterday */
    public List<HourSpend> hourlySpend(String id, String preset) {
        List<HourSpend> out = new ArrayList<>();
        Map<String, String> p = Map.of("level", "account", "date_preset", preset, "fields", "spend", "breakdowns",
                "hourly_stats_aggregated_by_advertiser_time_zone");
        Pattern hour = Pattern.compile("^\\s*(\\d+)");
        for (InsightRow r : graph.getAll(actOf(id) + "/insights", p, null, InsightRow.class)) {
            Matcher m = hour.matcher(r.hourlyStats() == null ? "0" : r.hourlyStats());
            out.add(new HourSpend(m.find() ? Integer.parseInt(m.group(1)) : 0, parseNum(r.spend())));
        }
        return out;
    }
}
