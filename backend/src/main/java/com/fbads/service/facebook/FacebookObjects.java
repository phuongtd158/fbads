package com.fbads.service.facebook;

import com.fbads.client.FbException;
import com.fbads.client.MockAds;
import com.fbads.client.RateLimits;
import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.dto.Responses.ObjectsMeta;
import com.fbads.dto.Responses.Usage;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookState.AccInfo;
import com.fbads.service.facebook.FacebookState.Cache0;
import com.fbads.service.facebook.FacebookState.Ws;
import com.fbads.service.facebook.GraphData.Account;
import com.fbads.service.facebook.GraphData.AdSet;
import com.fbads.service.facebook.GraphData.Campaign;
import com.fbads.service.facebook.GraphData.InsightRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.fbads.service.facebook.FacebookGraph.actOf;
import static com.fbads.service.facebook.FacebookParse.INSIGHT_FIELDS;
import static com.fbads.service.facebook.FacebookParse.budget;
import static com.fbads.service.facebook.FacebookParse.metricsFrom;
import static com.fbads.service.facebook.FacebookParse.ms;
import static com.fbads.service.facebook.FacebookState.FORCE_MIN_MS;
import static com.fbads.service.facebook.FacebookState.MOCK_TTL_MS;
import static com.fbads.service.facebook.FacebookState.TTL_BUSY_MS;
import static com.fbads.service.facebook.FacebookState.TTL_MS;

/**
 * Danh sách camp + nhóm QC kèm số liệu hôm nay, và bộ nhớ đệm của danh sách đó.
 * Giao diện, engine, rule, báo cáo đều đọc danh sách qua đây.
 */
@Service
public class FacebookObjects {
    private final SettingsService settings;
    private final RateLimits limits;
    private final FacebookGraph graph;
    private final FacebookState state;

    public FacebookObjects(SettingsService settings, RateLimits limits, FacebookGraph graph, FacebookState state) {
        this.settings = settings;
        this.limits = limits;
        this.graph = graph;
        this.state = state;
    }

    /**
     * Danh sách camp + nhóm QC kèm số liệu hôm nay. Dùng lại số đã tải:
     *  - tự làm mới (không ép): 2 phút; khi mức dùng API ≥ 60% thì 5 phút
     *  - ép (bấm "Làm mới", engine cần số mới): vẫn dùng lại nếu vừa tải trong 15 giây
     * Đang bị Facebook chặn: không gọi, trả số liệu gần nhất (stale).
     */
    public List<AdObject> listObjects(boolean force) {
        Ws w = state.ws();
        w.lock.lock();
        try {
            state.warmFromRedis();
            long now = System.currentTimeMillis(), age = now - w.cache.at();
            if (state.isMock()) {
                if (!force && w.cache.data() != null && age < MOCK_TTL_MS) return w.cache.data();
                w.cache = new Cache0(now, w.mock.list(), false);
                state.saveToRedis();
                return w.cache.data();
            }
            long ttl = force ? FORCE_MIN_MS : limits.pct() >= 60 ? TTL_BUSY_MS : TTL_MS;
            if (w.cache.data() != null && age < ttl) return w.cache.data();
            if (limits.blocked()) {
                if (w.cache.data() != null) {
                    w.cache = new Cache0(w.cache.at(), w.cache.data(), true);
                    return w.cache.data();
                }
                throw graph.rateLimitError();
            }
            try {
                List<AdObject> data = realList();
                w.cache = new Cache0(System.currentTimeMillis(), data, false);
                state.saveToRedis();
            } catch (FbException e) {
                if (w.cache.data() != null && e.isRateLimit()) {
                    w.cache = new Cache0(w.cache.at(), w.cache.data(), true);
                    return w.cache.data();
                }
                throw e;
            }
            return w.cache.data();
        } finally {
            w.lock.unlock();
        }
    }

    /** Danh sách đã tải (không gọi Facebook); null nếu chưa có */
    public List<AdObject> peekObjects() {
        return state.ws().cache.data();
    }

    /**
     * Danh sách để kiểm tra lịch/rule (camp có tồn tại không…): dùng bản đã tải, chưa có thì thử tải;
     * tải lỗi thì trả null và luật kiểm tra bỏ qua phần so với danh sách camp.
     */
    public List<AdObject> objectsForValidation() {
        List<AdObject> list = state.ws().cache.data();
        if (list != null) return list;
        try {
            return listObjects(false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public AdObject findCached(String id) {
        List<AdObject> list = state.ws().cache.data();
        if (list == null) return null;
        for (AdObject o : list) {
            if (o.id.equals(id)) return o;
        }
        return null;
    }

    /** true = danh sách đang là số cũ (Facebook đang chặn số lần gọi) */
    public boolean isStale() {
        return state.ws().cache.stale();
    }

    /** Thông tin kèm danh sách cho giao diện: số liệu lúc nào, có phải số cũ không, mức dùng API, tài khoản */
    public ObjectsMeta objectsMeta() {
        Ws w = state.ws();
        return new ObjectsMeta(w.cache.at() == 0 ? null : w.cache.at(), w.cache.stale(),
                limits.blocked() ? limits.blockedUntil() : null,
                limits.at() == 0 ? null : new Usage(limits.pct(), limits.tier()), accounts(),
                state.isMock() ? List.of() : w.accErrors);
    }

    /** Tài khoản quảng cáo đang quản lý: { id, name, currency } */
    public List<Map<String, Object>> accounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (state.isMock()) {
            for (MockAds.Account a : MockAds.ACCOUNTS) {
                out.add(new LinkedHashMap<>(Map.of("id", a.accountId(), "name", a.accountName(), "currency",
                        a.currency())));
            }
        } else {
            for (String id : settings.get().accountIds()) {
                AccInfo ai = state.ws().accInfo.get(id);
                out.add(new LinkedHashMap<>(Map.of("id", id, "name", ai != null ? ai.name() : id, "currency",
                        ai != null ? ai.currency() : "")));
            }
        }
        return out;
    }

    /** Ảnh chụp trạng thái một camp lúc thao tác (để xem trước/sau trong nhật ký) */
    public static Map<String, Object> snapshot(AdObject o) {
        if (o == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", o.level == null ? null : o.level.code());
        m.put("status", o.status);
        m.put("effective", o.effective);
        m.put("dailyBudget", o.dailyBudget);
        if (o.metrics != null) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("spend", o.metrics.spend());
            mm.put("results", o.metrics.results());
            mm.put("cpa", o.metrics.cpa());
            mm.put("roas", o.metrics.roas());
            m.put("metrics", mm);
        } else {
            m.put("metrics", null);
        }
        return m;
    }

    // ------------------------------------------------------------------ Tải từ Facebook

    /** Tải lần lượt từng tài khoản. Một tài khoản lỗi thì bỏ qua và báo; bị giới hạn số lần gọi thì dừng cả lượt. */
    private List<AdObject> realList() {
        Ws w = state.ws();
        List<String> ids = settings.get().accountIds();
        String rs = settings.get().getResultAction();
        if (ids.isEmpty()) throw new FbException("Chưa chọn tài khoản quảng cáo.", null);
        List<AdObject> out = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        RuntimeException first = null;
        for (String id : ids) {
            try {
                out.addAll(listAccount(id, rs));
            } catch (RuntimeException e) {
                if (FbException.isRateLimited(e)) throw e;
                if (first == null) first = e;
                AccInfo ai = w.accInfo.get(id);
                Map<String, Object> er = new LinkedHashMap<>();
                er.put("id", id);
                er.put("name", ai != null ? ai.name() : id);
                er.put("error", e.getMessage());
                errors.add(er);
            }
        }
        if (errors.size() == ids.size()) throw first;
        w.accErrors = errors;
        AccInfo a0 = w.accInfo.get(ids.getFirst());
        if (a0 != null) w.currency = a0.currency();
        return out;
    }

    /** Tên, tiền tệ, trạng thái của tài khoản; giữ 1 giờ */
    private AccInfo accountInfo(String id) {
        AccInfo c = state.ws().accInfo.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 3_600_000) return c;
        Account r = graph.get(actOf(id), Map.of("fields", "name,currency,account_status"), null, Account.class);
        AccInfo info = new AccInfo(r.name(), r.currency(), r.status(), System.currentTimeMillis());
        state.ws().accInfo.put(id, info);
        return info;
    }

    /** Một tài khoản: camp, nhóm QC, số liệu hôm nay của cả hai. 4 lượt gọi chạy song song (virtual thread). */
    private List<AdObject> listAccount(String id, String resultAction) {
        AccInfo info = accountInfo(id);
        String act = actOf(id);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Campaign>> fCamps = ex.submit(() -> graph.getAll(act + "/campaigns",
                    Map.of("fields", "id,name,status,effective_status,daily_budget"), null, Campaign.class));
            // learning_stage_info là thông tin phụ: Facebook từ chối trường này thì vẫn lấy danh sách như cũ (bị giới
            // hạn thì không thử lại)
            Future<List<AdSet>> fSets = ex.submit(() -> {
                try {
                    return graph.getAll(act + "/adsets", Map.of("fields",
                            "id,name,status,effective_status,daily_budget,campaign_id,start_time,end_time,"
                                    + "learning_stage_info"), null, AdSet.class);
                } catch (FbException e) {
                    if (e.isRateLimit()) throw e;
                    return graph.getAll(act + "/adsets", Map.of("fields",
                            "id,name,status,effective_status,daily_budget,campaign_id,start_time,end_time"), null, AdSet.class);
                }
            });
            Future<List<InsightRow>> fIc = ex.submit(() -> graph.getAll(act + "/insights",
                    Map.of("level", "campaign", "date_preset", "today", "fields", "campaign_id," + INSIGHT_FIELDS),
                    null, InsightRow.class));
            Future<List<InsightRow>> fIa = ex.submit(() -> graph.getAll(act + "/insights",
                    Map.of("level", "adset", "date_preset", "today", "fields", "adset_id," + INSIGHT_FIELDS), null,
                    InsightRow.class));
            List<Campaign> camps = get(fCamps);
            List<AdSet> adsets = get(fSets);

            Map<String, Metrics> mc = new LinkedHashMap<>(), ma = new LinkedHashMap<>();
            for (InsightRow r : get(fIc)) mc.put(r.campaignId(), metricsFrom(r, resultAction));
            for (InsightRow r : get(fIa)) ma.put(r.adsetId(), metricsFrom(r, resultAction));
            // camp "đang học" nếu có nhóm QC nào bên trong đang học
            Set<String> learningCamps = new HashSet<>();
            for (AdSet a : adsets) {
                if (a.isLearning()) learningCamps.add(a.campaignId());
            }

            List<AdObject> out = new ArrayList<>();
            for (Campaign c : camps) {
                AdObject o = base(c.id(), c.name(), AdLevel.CAMPAIGN, c.status(), c.effectiveStatus(), id, info);
                o.dailyBudget = budget(c.dailyBudget(), info.currency());
                o.learning = learningCamps.contains(o.id);
                o.metrics = mc.getOrDefault(o.id, Metrics.EMPTY);
                out.add(o);
            }
            for (AdSet a : adsets) {
                AdObject o = base(a.id(), a.name(), AdLevel.ADSET, a.status(), a.effectiveStatus(), id, info);
                o.campaignId = a.campaignId();
                o.dailyBudget = budget(a.dailyBudget(), info.currency());
                o.learning = a.isLearning();
                o.startTime = ms(a.startTime());
                o.endTime = ms(a.endTime());
                o.metrics = ma.getOrDefault(o.id, Metrics.EMPTY);
                out.add(o);
            }
            return out;
        }
    }

    private static AdObject base(String id, String name, AdLevel level, String status, String effective, String accountId,
            AccInfo info) {
        AdObject o = new AdObject();
        o.id = id;
        o.name = name;
        o.level = level;
        o.status = status;
        o.effective = effective;
        o.accountId = accountId;
        o.accountName = info.name();
        o.currency = info.currency();
        return o;
    }

    private static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }
}
