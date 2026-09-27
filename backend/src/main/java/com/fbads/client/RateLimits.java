package com.fbads.client;

import com.fbads.security.WorkspaceContext;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn số lần gọi của Facebook. Mỗi phản hồi có tiêu đề báo mức đã dùng (%); khi bị chặn Facebook báo số phút phải chờ
 * (estimated_time_to_regain_access) → tool ngưng đọc số liệu đến lúc đó (blockedUntil).
 * Mỗi workspace dùng token/tài khoản quảng cáo riêng nên có mức dùng riêng: workspace này bị chặn không làm workspace khác ngưng.
 */
@Component
public class RateLimits {
    private static final class Usage {
        volatile double pct;
        volatile String tier = "";
        volatile long at;
        volatile long blockedUntil;
    }

    private final JsonMapper mapper;
    private final Map<Long, Usage> byWorkspace = new ConcurrentHashMap<>();

    public RateLimits(JsonMapper mapper) { this.mapper = mapper; }

    private Usage u() { return byWorkspace.computeIfAbsent(WorkspaceContext.require(), k -> new Usage()); }

    public void readUsage(HttpHeaders headers) {
        Usage u = u();
        Double p = null;
        double regainMin = 0;
        for (String h : List.of("x-business-use-case-usage", "x-ad-account-usage", "x-app-usage")) {
            String raw = headers.getFirst(h);
            if (raw == null) continue;
            try {
                JsonNode j = mapper.readTree(raw);
                List<JsonNode> rows = new ArrayList<>();
                if (h.equals("x-business-use-case-usage")) {
                    for (JsonNode arr : j.values()) { if (arr.isArray()) arr.forEach(rows::add); else rows.add(arr); }
                } else rows.add(j);
                for (JsonNode r : rows) {
                    double m = Math.max(p == null ? 0 : p, Math.max(r.path("call_count").asDouble(0), Math.max(r.path("total_cputime").asDouble(0),
                            Math.max(r.path("total_time").asDouble(0), r.path("acc_id_util_pct").asDouble(0)))));
                    p = m;
                    regainMin = Math.max(regainMin, r.path("estimated_time_to_regain_access").asDouble(0));
                    String t = r.path("ads_api_access_tier").asString("");
                    if (!t.isEmpty()) u.tier = t;
                }
            } catch (RuntimeException ignored) { /* tiêu đề lạ: bỏ qua */ }
        }
        if (p != null) { u.pct = p; u.at = System.currentTimeMillis(); }
        if (regainMin > 0) block((long) (regainMin * 60_000));
    }

    /** Bị chặn thêm ms mili giây kể từ bây giờ (không rút ngắn thời gian chặn đang có) */
    public void block(long ms) {
        Usage u = u();
        synchronized (u) { u.blockedUntil = Math.max(u.blockedUntil, System.currentTimeMillis() + ms); }
    }

    public boolean blocked() { return System.currentTimeMillis() < u().blockedUntil; }

    public long blockedUntil() { return u().blockedUntil; }

    public double pct() { return u().pct; }

    public String tier() { return u().tier; }

    public long at() { return u().at; }

    /** Xoá mức dùng của workspace hiện tại */
    public void reset() { byWorkspace.remove(WorkspaceContext.require()); }
}
