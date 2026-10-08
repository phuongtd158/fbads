package com.fbads.service.facebook;

import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;
import com.fbads.ads.ObjectsMeta.AccountError;
import com.fbads.ads.Trend.TrendDay;
import com.fbads.client.MockAds;
import com.fbads.config.CacheConfig;
import com.fbads.dto.FbSnapshots;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.SettingsService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Số đã tải từ Facebook, riêng cho từng workspace (WorkspaceContext): danh sách camp, số theo khoảng, xu hướng,
 * thông tin tài khoản, tiền tệ, dữ liệu giả. Workspace khác nhau không dùng chung gì.
 * <p>
 * Hai tầng bộ nhớ đệm:
 *  - tầng 1 trong bộ nhớ (lớp {@link Ws}): engine sửa trực tiếp các AdObject trong đó;
 *  - tầng 2 ở Redis (fb-objects, fb-ranges): khởi động lại thì lấy lại từ đây, khỏi gọi Facebook ngay.
 * Khoá ReentrantLock (không dùng synchronized) để không "ghim" virtual thread khi đang chờ mạng.
 */
@Component
public class FacebookState {
    static final long FORCE_MIN_MS = 15_000, TTL_MS = 120_000, TTL_BUSY_MS = 300_000, RANGE_TTL_MS = 240_000,
            MOCK_TTL_MS = 45_000;

    record AccInfo(String name, String currency, int status, long at) {}

    record Cache0(long at, List<AdObject> data, boolean stale) {}

    record TokenError(String token, String message) {}

    static final class RangeEntry {
        final long at;
        final Map<String, Metrics> data;
        final boolean mock;
        volatile boolean stale;

        RangeEntry(long at, Map<String, Metrics> data, boolean mock) {
            this.at = at;
            this.data = data;
            this.mock = mock;
        }
    }

    static final class TrendEntry {
        final long at;
        final List<TrendDay> days;
        volatile boolean stale;

        TrendEntry(long at, List<TrendDay> days) {
            this.at = at;
            this.days = days;
        }
    }

    /** Trạng thái riêng của một workspace */
    static final class Ws {
        final MockAds mock = new MockAds();
        final ReentrantLock lock = new ReentrantLock();
        final Map<String, AccInfo> accInfo = new ConcurrentHashMap<>();
        final Map<String, RangeEntry> rangeCache = new ConcurrentHashMap<>();
        final Map<String, TrendEntry> trendCache = new ConcurrentHashMap<>();
        volatile Cache0 cache = new Cache0(0, null, false);
        volatile List<AccountError> accErrors = List.of();
        volatile String currency = "VND";
        volatile TokenError tokenError;
    }

    private final Map<Long, Ws> states = new ConcurrentHashMap<>();
    private final SettingsService settings;
    final Cache objectsL2, rangesL2;

    public FacebookState(SettingsService settings, CacheManager caches) {
        this.settings = settings;
        this.objectsL2 = caches.getCache(CacheConfig.OBJECTS);
        this.rangesL2 = caches.getCache(CacheConfig.RANGES);
    }

    /** Trạng thái của workspace hiện tại (WorkspaceContext) */
    Ws ws() {
        return states.computeIfAbsent(WorkspaceContext.require(), k -> new Ws());
    }

    boolean isMock() {
        return settings.get().isMock();
    }

    /** Danh sách camp đánh dấu "cần tải lại": lần đọc sau gọi Facebook (giữ dữ liệu cũ để vẫn hiện được) */
    void expireObjects() {
        Ws w = ws();
        w.cache = new Cache0(0, w.cache.data(), w.cache.stale());
    }

    /**
     * Khoá cache ở Redis theo workspace và nguồn dữ liệu: đổi tài khoản / dữ liệu giả thì không dùng nhầm số của
     * nguồn khác, và hai workspace không bao giờ đọc số của nhau (kể cả khi cùng tài khoản quảng cáo: mỗi bên một
     * token, quyền có thể khác).
     */
    String sourceKey() {
        return "ws" + WorkspaceContext.require() + "|"
                + (isMock() ? "mock" : "live:" + String.join(",", settings.get().accountIds()));
    }

    /** Xoá bản ở Redis của workspace hiện tại (danh sách camp; và các khoảng ngày đang có trong bộ nhớ) */
    void evictL2(boolean ranges) {
        try {
            objectsL2.evict(sourceKey());
            if (ranges) {
                for (String k : ws().rangeCache.keySet()) rangesL2.evict(sourceKey() + "|" + k);
            }
        } catch (RuntimeException ignored) {
            // Redis lỗi: bản cũ tự hết hạn
        }
    }

    /** Vừa khởi động (tầng 1 trống): lấy bản đã lưu ở Redis, nếu có */
    void warmFromRedis() {
        Ws w = ws();
        if (w.cache.data() != null) return;
        FbSnapshots.Objects s = get(objectsL2, sourceKey(), FbSnapshots.Objects.class);
        if (s == null || s.items() == null) return;
        w.cache = new Cache0(s.at(), s.items(), false);
        w.accErrors = s.accountErrors() == null ? List.of() : s.accountErrors();
        if (s.currency() != null) w.currency = s.currency();
    }

    void saveToRedis() {
        Ws w = ws();
        put(objectsL2, sourceKey(), new FbSnapshots.Objects(w.cache.at(), w.cache.data(), w.accErrors, w.currency));
    }

    /** Redis lỗi thì coi như không có cache (tool vẫn chạy, chỉ gọi Facebook nhiều hơn) */
    static <T> T get(Cache c, String key, Class<T> type) {
        try {
            return c.get(key, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void put(Cache c, String key, Object value) {
        try {
            c.put(key, value);
        } catch (RuntimeException ignored) {
            // như trên
        }
    }

    /** Đổi nguồn dữ liệu (token, tài khoản, bật/tắt dữ liệu giả): bỏ hết số đã lưu của workspace này, cả ở Redis */
    @CacheEvict(cacheNames = CacheConfig.ACCOUNTS, allEntries = true)
    public void resetCache() {
        evictL2(true);
        Ws w = ws();
        w.cache = new Cache0(0, null, false);
        w.accErrors = List.of();
        w.accInfo.clear();
        w.rangeCache.clear();
        w.trendCache.clear();
        w.tokenError = null;
    }

    /** Dữ liệu giả về lại như lúc đầu (cho kiểm thử) */
    public void resetMock() {
        ws().mock.reset();
    }

    /** Tiền tệ của tài khoản đầu tiên */
    public String currency() {
        return ws().currency;
    }
}
