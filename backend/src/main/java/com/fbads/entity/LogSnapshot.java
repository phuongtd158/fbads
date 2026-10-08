package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.JsonConverters;
import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;

/**
 * Nhật ký: camp/nhóm trông thế nào ngay trước thao tác (để xem lại và hoàn tác).
 * Ghi đủ mọi trường kể cả null (dailyBudget null = không có ngân sách riêng), như bản Node.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogSnapshot(AdLevel level, String status, String effective, Double dailyBudget, Metrics metrics) {
    /** Vài số liệu lúc đó; cpa/roas null khi chưa tính được */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metrics(Double spend, Double results, Double cpa, Double roas) {}

    public static LogSnapshot of(AdObject o) {
        if (o == null) return null;
        Metrics m = o.metrics == null ? null
                : new Metrics(o.metrics.spend(), o.metrics.results(), o.metrics.cpa(), o.metrics.roas());
        return new LogSnapshot(o.level, o.status, o.effective, o.dailyBudget, m);
    }

    public boolean wasActive() { return "ACTIVE".equals(status); }

    public static class Converter extends JsonConverters.Of<LogSnapshot> {
        public Converter() { super(LogSnapshot.class); }
    }
}
