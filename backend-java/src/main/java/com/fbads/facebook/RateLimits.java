package com.fbads.facebook;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Giới hạn số lần gọi của Facebook. Mỗi phản hồi có tiêu đề báo mức đã dùng (%); khi bị chặn Facebook báo số phút phải chờ
 * (estimated_time_to_regain_access) → tool ngưng đọc số liệu đến lúc đó (blockedUntil).
 */
@Component
public class RateLimits {
    private final JsonMapper mapper;
    private volatile double pct;
    private volatile String tier = "";
    private volatile long at;
    private volatile long blockedUntil;

    public RateLimits(JsonMapper mapper) { this.mapper = mapper; }

    public void readUsage(HttpHeaders headers) {
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
                    if (!t.isEmpty()) tier = t;
                }
            } catch (RuntimeException ignored) { /* tiêu đề lạ: bỏ qua */ }
        }
        if (p != null) { pct = p; at = System.currentTimeMillis(); }
        if (regainMin > 0) block((long) (regainMin * 60_000));
    }

    /** Bị chặn thêm ms mili giây kể từ bây giờ (không rút ngắn thời gian chặn đang có) */
    public synchronized void block(long ms) { blockedUntil = Math.max(blockedUntil, System.currentTimeMillis() + ms); }

    public boolean blocked() { return System.currentTimeMillis() < blockedUntil; }

    public long blockedUntil() { return blockedUntil; }

    public double pct() { return pct; }

    public String tier() { return tier; }

    public long at() { return at; }

    public void reset() { pct = 0; tier = ""; at = 0; blockedUntil = 0; }
}
