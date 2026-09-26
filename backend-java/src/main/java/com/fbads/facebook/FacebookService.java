package com.fbads.facebook;

import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Client cho Facebook Marketing API + chế độ giả lập (mock). Bản Java của lib/fb.js.
 * Bộ nhớ đệm (cache) dùng chung cho giao diện và engine; khoá ReentrantLock (không dùng synchronized)
 * để không "ghim" virtual thread khi đang chờ mạng.
 */
@Service
public class FacebookService {
    private static final Set<String> NO_DECIMAL = Set.of("VND", "JPY", "KRW", "CLP", "ISK", "PYG");
    private static final Set<String> SECRET_KEYS = Set.of("access_token", "client_secret", "fb_exchange_token", "input_token", "code");
    static final long FORCE_MIN_MS = 15_000, TTL_MS = 120_000, TTL_BUSY_MS = 300_000, RANGE_TTL_MS = 240_000, MOCK_TTL_MS = 45_000;
    private static final Map<String, List<String>> RESULT_ALIASES = Map.of(
            "purchase", List.of("omni_purchase", "purchase", "onsite_conversion.purchase", "offsite_conversion.fb_pixel_purchase", "onsite_web_purchase"),
            "lead", List.of("lead", "onsite_conversion.lead_grouped", "offsite_conversion.fb_pixel_lead", "onsite_web_lead"),
            "initiate_checkout", List.of("omni_initiated_checkout", "initiate_checkout", "onsite_conversion.initiate_checkout", "offsite_conversion.fb_pixel_initiate_checkout"));
    private static final Map<Integer, String> ACC_STATUS = Map.of(1, "Đang hoạt động", 2, "Bị vô hiệu hoá", 3, "Nợ thanh toán", 7, "Đang xét duyệt rủi ro",
            8, "Đang xử lý thanh toán", 9, "Trong thời gian gia hạn", 100, "Đang chờ đóng", 101, "Đã đóng", 201, "Đang chờ", 202, "Đã đóng");
    public static final List<String> NEED_SCOPES = List.of("ads_management", "ads_read");
    private static final Map<String, Integer> ENGINE_DAYS = Map.of("yesterday", 1, "last_3d", 3, "last_7d", 7);

    /** Khoảng thời gian cho số liệu: key = khoá bộ nhớ đệm, fbParams = date_preset / time_range, days = số ngày (cho dữ liệu giả) */
    public record RangeQuery(String key, Map<String, String> fbParams, Integer days) {}

    public record RangeResult(Map<String, Metrics> data, Long at, boolean stale) {}

    private record AccInfo(String name, String currency, int status, long at) {}

    private record Cache(long at, List<AdObject> data, boolean stale) {}

    private static final class RangeEntry {
        final long at; final Map<String, Metrics> data; final boolean mock; volatile boolean stale;
        RangeEntry(long at, Map<String, Metrics> data, boolean mock) { this.at = at; this.data = data; this.mock = mock; }
    }

    private final SettingsService settings;
    private final GraphClient graph;
    private final RateLimits limits;
    private final MockAds mock = new MockAds();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, AccInfo> accInfo = new ConcurrentHashMap<>();
    private final Map<String, RangeEntry> rangeCache = new ConcurrentHashMap<>();
    private volatile Cache cache = new Cache(0, null, false);
    private volatile List<Map<String, Object>> accErrors = List.of();
    private volatile String currency = "VND";

    public FacebookService(SettingsService settings, GraphClient graph, RateLimits limits) {
        this.settings = settings;
        this.graph = graph;
        this.limits = limits;
    }

    private static double offsetOf(String cur) { return NO_DECIMAL.contains(cur) ? 1 : 100; }

    private boolean isMock() { return settings.get().isMock(); }

    // ------------------------------------------------------------------ Graph API
    private static Map<String, Object> cleanParams(Map<String, String> p) {
        Map<String, Object> o = new LinkedHashMap<>();
        p.forEach((k, v) -> { if (!SECRET_KEYS.contains(k)) o.put(k, v.length() > 300 ? v.substring(0, 300) : v); });
        return o;
    }

    JsonNode call(String method, String path, Map<String, String> params, String tokenOverride) {
        AppSettings s = settings.get();
        String token = tokenOverride != null ? tokenOverride : s.getAccessToken();
        if (token == null || token.isEmpty()) throw new FbException("Chưa nhập Access Token trong Cài đặt", null);
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("access_token", token);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("path", "/" + s.getApiVersion() + "/" + path);
        request.put("params", cleanParams(params));
        String base = GraphClient.BASE + "/" + s.getApiVersion() + "/" + path;
        if (method.equals("GET")) return send(HttpMethod.GET, URI.create(base + "?" + GraphClient.form(all)), null, request);
        return send(HttpMethod.valueOf(method), URI.create(base), GraphClient.form(all), request);
    }

    private JsonNode send(HttpMethod m, URI uri, String body, Map<String, Object> request) {
        try {
            return graph.send(m, uri, body, request);
        } catch (ResourceAccessException e) {
            Map<String, Object> fb = new LinkedHashMap<>();
            fb.put("network", true);
            fb.put("systemMessage", String.valueOf(e.getMostSpecificCause().getMessage()));
            fb.put("request", request);
            throw new FbException("Không kết nối được tới Facebook. Kiểm tra mạng internet.", fb);
        }
    }

    /** Lấy hết các trang (500 mục/trang để ít lượt gọi) */
    List<JsonNode> callAll(String path, Map<String, String> params, String token) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("limit", "500");
        p.putAll(params);
        JsonNode json = call("GET", path, p, token);
        List<JsonNode> out = new ArrayList<>();
        json.path("data").forEach(out::add);
        while (json.path("paging").hasNonNull("next")) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("method", "GET");
            req.put("path", "/" + path);
            req.put("params", Map.of("page", "next"));
            json = send(HttpMethod.GET, URI.create(json.path("paging").path("next").asString()), null, req);
            json.path("data").forEach(out::add);
        }
        return out;
    }

    private static String actOf(String id) { return "act_" + id; }

    private AccInfo accountInfo(String id) {
        AccInfo c = accInfo.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 3_600_000) return c;
        JsonNode r = call("GET", actOf(id), Map.of("fields", "name,currency,account_status"), null);
        AccInfo info = new AccInfo(r.path("name").asString(""), r.path("currency").asString(""), r.path("account_status").asInt(0), System.currentTimeMillis());
        accInfo.put(id, info);
        return info;
    }

    // ------------------------------------------------------------------ Số liệu
    private static double pick(JsonNode arr, List<String> names) {
        if (arr == null || !arr.isArray()) return 0;
        for (String n : names) for (JsonNode a : arr) if (n.equals(a.path("action_type").asString(""))) return parseNum(a.path("value"));
        return 0;
    }

    private static double parseNum(JsonNode v) {
        try { return v == null || v.isNull() || v.isMissingNode() ? 0 : Double.parseDouble(v.asString()); } catch (NumberFormatException e) { return 0; }
    }

    /** Một dòng Insights → số liệu. Loại "kết quả" có nhiều tên tuỳ nơi phát sinh: lấy tên ĐẦU TIÊN có trong số liệu (không cộng dồn). */
    public static Metrics metricsFrom(JsonNode row, String resultAction) {
        double spend = parseNum(row.path("spend"));
        List<String> names = RESULT_ALIASES.getOrDefault(resultAction, List.of(resultAction));
        double results = pick(row.get("actions"), names), value = pick(row.get("action_values"), names);
        return new Metrics(spend, (long) parseNum(row.path("impressions")), (long) parseNum(row.path("reach")), (long) parseNum(row.path("clicks")), results,
                results > 0 ? spend / results : null, value, spend > 0 ? value / spend : null,
                pick(row.get("actions"), List.of("onsite_conversion.messaging_conversation_started_7d")),
                pick(row.get("actions"), RESULT_ALIASES.get("initiate_checkout")),
                pick(row.get("actions"), RESULT_ALIASES.get("lead")),
                pick(row.get("actions"), List.of("onsite_conversion.lead_grouped")),
                pick(row.get("actions"), List.of("comment")));
    }

    private static Long ms(JsonNode t) {
        if (t == null || t.isNull() || t.isMissingNode()) return null;
        try {
            return OffsetDateTime.parse(t.asString().replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final String INSIGHT_FIELDS = "spend,impressions,reach,clicks,actions,action_values";

    private List<AdObject> listAccount(String id, String resultAction) {
        AccInfo info = accountInfo(id);
        String act = actOf(id);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<JsonNode>> fCamps = ex.submit(() -> callAll(act + "/campaigns", Map.of("fields", "id,name,status,effective_status,daily_budget"), null));
            // learning_stage_info là thông tin phụ: Facebook từ chối trường này thì vẫn lấy danh sách như cũ (bị giới hạn thì không thử lại)
            Future<List<JsonNode>> fSets = ex.submit(() -> {
                try {
                    return callAll(act + "/adsets", Map.of("fields", "id,name,status,effective_status,daily_budget,campaign_id,start_time,end_time,learning_stage_info"), null);
                } catch (FbException e) {
                    if (e.isRateLimit()) throw e;
                    return callAll(act + "/adsets", Map.of("fields", "id,name,status,effective_status,daily_budget,campaign_id,start_time,end_time"), null);
                }
            });
            Future<List<JsonNode>> fIc = ex.submit(() -> callAll(act + "/insights", Map.of("level", "campaign", "date_preset", "today", "fields", "campaign_id," + INSIGHT_FIELDS), null));
            Future<List<JsonNode>> fIa = ex.submit(() -> callAll(act + "/insights", Map.of("level", "adset", "date_preset", "today", "fields", "adset_id," + INSIGHT_FIELDS), null));
            List<JsonNode> camps = get(fCamps), adsets = get(fSets), ic = get(fIc), ia = get(fIa);

            Map<String, Metrics> mc = new LinkedHashMap<>(), ma = new LinkedHashMap<>();
            for (JsonNode r : ic) mc.put(r.path("campaign_id").asString(), metricsFrom(r, resultAction));
            for (JsonNode r : ia) ma.put(r.path("adset_id").asString(), metricsFrom(r, resultAction));
            Function<JsonNode, Double> conv = v -> v == null || v.isNull() || v.isMissingNode() || v.asString().isEmpty() ? null : Long.parseLong(v.asString()) / offsetOf(info.currency());
            Function<JsonNode, Boolean> isLearning = a -> "LEARNING".equals(a.path("learning_stage_info").path("status").asString(""));
            Set<String> learningCamps = new HashSet<>();
            for (JsonNode a : adsets) if (isLearning.apply(a)) learningCamps.add(a.path("campaign_id").asString());

            List<AdObject> out = new ArrayList<>();
            for (JsonNode c : camps) {
                AdObject o = base(c, "campaign", id, info);
                o.dailyBudget = conv.apply(c.get("daily_budget"));
                o.learning = learningCamps.contains(o.id);
                o.metrics = mc.getOrDefault(o.id, Metrics.EMPTY);
                out.add(o);
            }
            for (JsonNode a : adsets) {
                AdObject o = base(a, "adset", id, info);
                o.campaignId = a.path("campaign_id").asString();
                o.dailyBudget = conv.apply(a.get("daily_budget"));
                o.learning = isLearning.apply(a);
                o.startTime = ms(a.get("start_time"));
                o.endTime = ms(a.get("end_time"));
                o.metrics = ma.getOrDefault(o.id, Metrics.EMPTY);
                out.add(o);
            }
            return out;
        }
    }

    private static AdObject base(JsonNode n, String level, String accountId, AccInfo info) {
        AdObject o = new AdObject();
        o.id = n.path("id").asString();
        o.name = n.path("name").asString("");
        o.level = level;
        o.status = n.path("status").asString("");
        o.effective = n.path("effective_status").asString("");
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

    /** Tải lần lượt từng tài khoản. Một tài khoản lỗi thì bỏ qua và báo; bị giới hạn số lần gọi thì dừng cả lượt. */
    private List<AdObject> realList() {
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
                AccInfo ai = accInfo.get(id);
                Map<String, Object> er = new LinkedHashMap<>();
                er.put("id", id);
                er.put("name", ai != null ? ai.name() : id);
                er.put("error", e.getMessage());
                errors.add(er);
            }
        }
        if (errors.size() == ids.size()) throw first;
        accErrors = errors;
        AccInfo a0 = accInfo.get(ids.getFirst());
        if (a0 != null) currency = a0.currency();
        return out;
    }

    /**
     * Danh sách camp + nhóm QC kèm số liệu hôm nay. Dùng lại số đã tải:
     *  - tự làm mới (không ép): 2 phút; khi mức dùng API ≥ 60% thì 5 phút
     *  - ép (bấm "Làm mới", engine cần số mới): vẫn dùng lại nếu vừa tải trong 15 giây
     * Đang bị Facebook chặn: không gọi, trả số liệu gần nhất (stale).
     */
    public List<AdObject> listObjects(boolean force) {
        lock.lock();
        try {
            long now = System.currentTimeMillis(), age = now - cache.at();
            if (isMock()) {
                if (!force && cache.data() != null && age < MOCK_TTL_MS) return cache.data();
                cache = new Cache(now, mock.list(), false);
                return cache.data();
            }
            long ttl = force ? FORCE_MIN_MS : limits.pct() >= 60 ? TTL_BUSY_MS : TTL_MS;
            if (cache.data() != null && age < ttl) return cache.data();
            if (limits.blocked()) {
                if (cache.data() != null) { cache = new Cache(cache.at(), cache.data(), true); return cache.data(); }
                throw rateLimitError();
            }
            try {
                List<AdObject> data = realList();
                cache = new Cache(System.currentTimeMillis(), data, false);
            } catch (FbException e) {
                if (cache.data() != null && e.isRateLimit()) { cache = new Cache(cache.at(), cache.data(), true); return cache.data(); }
                throw e;
            }
            return cache.data();
        } finally {
            lock.unlock();
        }
    }

    private FbException rateLimitError() {
        return new FbException(GraphClient.friendly(17, 0, "", limits), new LinkedHashMap<>(Map.of("code", 17)));
    }

    /** Danh sách đã tải (không gọi Facebook); null nếu chưa có */
    public List<AdObject> peekObjects() { return cache.data(); }

    /**
     * Danh sách để kiểm tra lịch/rule (camp có tồn tại không…): dùng bản đã tải, chưa có thì thử tải;
     * tải lỗi thì trả null và luật kiểm tra bỏ qua phần so với danh sách camp.
     */
    public List<AdObject> objectsForValidation() {
        List<AdObject> list = cache.data();
        if (list != null) return list;
        try {
            return listObjects(false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public AdObject findCached(String id) {
        List<AdObject> list = cache.data();
        if (list == null) return null;
        for (AdObject o : list) if (o.id.equals(id)) return o;
        return null;
    }

    public boolean isStale() { return cache.stale(); }

    /** Thông tin kèm danh sách cho giao diện: số liệu lúc nào, có phải số cũ không, mức dùng API, tài khoản */
    public Map<String, Object> objectsMeta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", cache.at() == 0 ? null : cache.at());
        m.put("stale", cache.stale());
        m.put("blockedUntil", limits.blocked() ? limits.blockedUntil() : null);
        m.put("usage", limits.at() == 0 ? null : Map.of("pct", limits.pct(), "tier", limits.tier()));
        m.put("accounts", accounts());
        m.put("accountErrors", isMock() ? List.of() : accErrors);
        return m;
    }

    public List<Map<String, Object>> accounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (isMock()) {
            for (MockAds.Account a : MockAds.ACCOUNTS) out.add(new LinkedHashMap<>(Map.of("id", a.accountId(), "name", a.accountName(), "currency", a.currency())));
        } else {
            for (String id : settings.get().accountIds()) {
                AccInfo ai = accInfo.get(id);
                out.add(new LinkedHashMap<>(Map.of("id", id, "name", ai != null ? ai.name() : id, "currency", ai != null ? ai.currency() : "")));
            }
        }
        return out;
    }

    /** Ảnh chụp trạng thái một camp lúc thao tác (để xem trước/sau trong nhật ký) */
    public static Map<String, Object> snapshot(AdObject o) {
        if (o == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", o.level);
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
        } else m.put("metrics", null);
        return m;
    }

    public void resetCache() {
        cache = new Cache(0, null, false);
        accErrors = List.of();
        accInfo.clear();
        rangeCache.clear();
    }

    public void resetMock() { mock.reset(); }

    public String currency() { return currency; }

    // ------------------------------------------------------------------ Khoảng thời gian
    private Map<String, Metrics> fetchRange(Map<String, String> fbParams) {
        String rs = settings.get().getResultAction();
        Map<String, Metrics> data = new LinkedHashMap<>();
        for (String id : settings.get().accountIds()) {
            try {
                Map<String, String> pc = new LinkedHashMap<>(Map.of("level", "campaign", "fields", "campaign_id," + INSIGHT_FIELDS));
                pc.putAll(fbParams);
                Map<String, String> pa = new LinkedHashMap<>(Map.of("level", "adset", "fields", "adset_id," + INSIGHT_FIELDS));
                pa.putAll(fbParams);
                for (JsonNode r : callAll(actOf(id) + "/insights", pc, null)) data.put(r.path("campaign_id").asString(), metricsFrom(r, rs));
                for (JsonNode r : callAll(actOf(id) + "/insights", pa, null)) data.put(r.path("adset_id").asString(), metricsFrom(r, rs));
            } catch (FbException e) {
                if (e.isRateLimit()) throw e; // tài khoản lỗi khác: bỏ qua (đã báo ở danh sách camp)
            }
        }
        return data;
    }

    /** Số liệu theo khoảng: { [id]: metrics }. 'p:today' dùng lại danh sách camp; khoảng khác có bộ nhớ đệm riêng. */
    public RangeResult rangeData(RangeQuery q, boolean force) {
        if ("p:today".equals(q.key())) {
            List<AdObject> list = listObjects(force);
            Map<String, Metrics> m = new LinkedHashMap<>();
            for (AdObject o : list) m.put(o.id, o.metrics);
            return new RangeResult(m, cache.at(), cache.stale());
        }
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            RangeEntry c = rangeCache.get(q.key());
            boolean isMock = isMock();
            long ttl = force ? FORCE_MIN_MS : limits.pct() >= 60 ? TTL_BUSY_MS : RANGE_TTL_MS;
            if (c != null && c.mock == isMock && now - c.at < ttl) return new RangeResult(c.data, c.at, c.stale);
            boolean usable = c != null && c.mock == isMock;
            if (!isMock && limits.blocked()) {
                if (usable) { c.stale = true; return new RangeResult(c.data, c.at, true); }
                throw rateLimitError();
            }
            Map<String, Metrics> data;
            try {
                data = isMock ? mock.range(q.days() == null ? 90 : q.days()) : fetchRange(q.fbParams());
            } catch (FbException e) {
                if (usable && e.isRateLimit()) { c.stale = true; return new RangeResult(c.data, c.at, true); }
                throw e;
            }
            RangeEntry e = new RangeEntry(System.currentTimeMillis(), data, isMock);
            rangeCache.put(q.key(), e);
            return new RangeResult(data, e.at, false);
        } finally {
            lock.unlock();
        }
    }

    /** Cho rule: 'today' | 'yesterday' | 'last_3d' | 'last_7d' → { [id]: metrics } */
    public Map<String, Metrics> rangeMetrics(String range, boolean force) {
        if (range == null || range.equals("today")) return rangeData(new RangeQuery("p:today", null, null), force).data();
        return rangeData(new RangeQuery("p:" + range, Map.of("date_preset", range), ENGINE_DAYS.getOrDefault(range, 1)), force).data();
    }

    // ------------------------------------------------------------------ Thay đổi
    public void setStatus(String id, boolean on) {
        if (isMock()) mock.setStatus(id, on);
        else call("POST", id, Map.of("status", on ? "ACTIVE" : "PAUSED"), null);
        cache = new Cache(0, cache.data(), cache.stale());
    }

    public void setBudget(String id, double amount) {
        long rounded = Math.round(amount);
        if (isMock()) mock.setBudget(id, rounded);
        else {
            AdObject o = findCached(id);
            String cur = o != null && o.currency != null ? o.currency : currency;
            call("POST", id, Map.of("daily_budget", Long.toString(Math.round(rounded * offsetOf(cur)))), null);
        }
        cache = new Cache(0, cache.data(), cache.stale());
    }

    // ------------------------------------------------------------------ Kết nối & token
    public Map<String, Object> inspectToken(String token) {
        JsonNode d = call("GET", "debug_token", Map.of("input_token", token), token).path("data");
        List<String> scopes = new ArrayList<>();
        d.path("scopes").forEach(x -> scopes.add(x.asString()));
        long exp = d.path("expires_at").asLong(0) * 1000;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", !(d.has("is_valid") && !d.path("is_valid").asBoolean(true)));
        m.put("expiresAt", exp == 0 ? null : exp);
        m.put("daysLeft", exp == 0 ? null : Math.floorDiv(exp - System.currentTimeMillis(), 86_400_000L));
        m.put("scopes", scopes);
        m.put("missing", NEED_SCOPES.stream().filter(x -> !scopes.contains(x)).toList());
        m.put("type", d.path("type").asString(""));
        m.put("appId", d.path("app_id").asString(""));
        return m;
    }

    private Map<String, Object> tryInspect(String token) {
        try { return inspectToken(token); } catch (RuntimeException e) { return null; }
    }

    public Map<String, Object> listAccounts(String token) {
        JsonNode me = call("GET", "me", Map.of("fields", "name"), token);
        List<JsonNode> list = callAll("me/adaccounts", Map.of("fields", "account_id,name,currency,account_status"), token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", me.path("name").asString(""));
        out.put("token", tryInspect(token));
        List<Map<String, Object>> accs = new ArrayList<>();
        for (JsonNode a : list) {
            int st = a.path("account_status").asInt(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.path("account_id").asString());
            m.put("name", a.path("name").asString(""));
            m.put("currency", a.path("currency").asString(""));
            m.put("status", ACC_STATUS.getOrDefault(st, String.valueOf(st)));
            m.put("active", st == 1);
            accs.add(m);
        }
        out.put("accounts", accs);
        return out;
    }

    /** Đổi token ngắn hạn (vài giờ) thành token dài hạn (~60 ngày) */
    public String extendToken(String appId, String appSecret, String token) {
        return call("GET", "oauth/access_token", Map.of("grant_type", "fb_exchange_token", "client_id", appId, "client_secret", appSecret, "fb_exchange_token", token), token)
                .path("access_token").asString(null);
    }

    /** Trang đăng nhập của Facebook; sau khi cho phép, Facebook chuyển về redirectUri kèm ?code=…&state=… */
    public String oauthUrl(String appId, String configId, String redirectUri, String state) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", appId);
        p.put("redirect_uri", redirectUri);
        p.put("state", state);
        p.put("response_type", "code");
        if (configId != null && !configId.isEmpty()) p.put("config_id", configId);
        else { p.put("scope", String.join(",", NEED_SCOPES)); p.put("auth_type", "rerequest"); }
        return "https://www.facebook.com/" + settings.get().getApiVersion() + "/dialog/oauth?" + GraphClient.form(p);
    }

    /** Đổi code lấy token rồi gia hạn lên ~60 ngày (gia hạn lỗi thì vẫn dùng token ngắn hạn) */
    public String exchangeCode(String appId, String appSecret, String redirectUri, String code) {
        String appToken = appId + "|" + appSecret;
        JsonNode r = call("GET", "oauth/access_token", Map.of("client_id", appId, "client_secret", appSecret, "redirect_uri", redirectUri, "code", code), appToken);
        String t = r.path("access_token").asString(null);
        if (t == null) throw new FbException("Facebook không trả về token.", null);
        try {
            String longT = extendToken(appId, appSecret, t);
            return longT != null ? longT : t;
        } catch (RuntimeException e) {
            return t;
        }
    }

    public Map<String, Object> testConnection() {
        if (isMock()) return new LinkedHashMap<>(Map.of("ok", true, "mock", true, "name", "Chế độ dùng thử (dữ liệu giả)", "currency", "VND"));
        AppSettings s = settings.get();
        List<String> ids = s.accountIds();
        if (s.getAccessToken().isEmpty()) throw new FbException("Chưa có Access Token.", null);
        if (ids.isEmpty()) throw new FbException("Chưa chọn tài khoản quảng cáo.", null);
        JsonNode me = call("GET", "me", Map.of("fields", "name"), null);
        Map<String, Object> tk = tryInspect(s.getAccessToken());
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", id);
            try {
                JsonNode r = call("GET", actOf(id), Map.of("fields", "name,currency,account_status"), null);
                int st = r.path("account_status").asInt(0);
                accInfo.put(id, new AccInfo(r.path("name").asString(""), r.path("currency").asString(""), st, System.currentTimeMillis()));
                a.put("name", r.path("name").asString(""));
                a.put("currency", r.path("currency").asString(""));
                a.put("status", ACC_STATUS.getOrDefault(st, String.valueOf(st)));
                a.put("active", st == 1);
            } catch (FbException e) {
                if (e.isRateLimit()) throw e;
                AccInfo ai = accInfo.get(id);
                a.put("name", ai != null ? ai.name() : id);
                a.put("status", "Lỗi");
                a.put("active", false);
                a.put("error", e.getMessage());
            }
            accounts.add(a);
        }
        List<Map<String, Object>> ok = accounts.stream().filter(a -> !a.containsKey("error")).toList();
        if (ok.isEmpty()) throw new FbException((String) accounts.getFirst().get("error"), null);
        currency = (String) ok.getFirst().get("currency");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", me.path("name").asString(""));
        out.put("accounts", accounts);
        out.put("name", accounts.size() > 1 ? accounts.size() + " tài khoản quảng cáo" : accounts.getFirst().get("name"));
        out.put("currency", String.join(", ", new LinkedHashSet<>(ok.stream().map(a -> (String) a.get("currency")).toList())));
        long activeN = accounts.stream().filter(a -> Boolean.TRUE.equals(a.get("active"))).count();
        out.put("status", accounts.size() > 1 ? activeN + "/" + accounts.size() + " đang hoạt động" : accounts.getFirst().get("status"));
        out.put("accountActive", activeN == accounts.size());
        out.put("token", tk);
        out.put("checkedAt", System.currentTimeMillis());
        return out;
    }
}
