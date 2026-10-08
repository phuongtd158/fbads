package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.JsonConverters;

/** Nhật ký: giá trị sau thay đổi. Chỉ có một trong hai: status (bật/tắt) hoặc dailyBudget (đổi ngân sách). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogChange(String status, Double dailyBudget) {
    public static LogChange status(boolean active) { return new LogChange(active ? "ACTIVE" : "PAUSED", null); }

    public static LogChange budget(double dailyBudget) { return new LogChange(null, dailyBudget); }

    public boolean changesStatus() { return status != null; }

    public boolean changesBudget() { return dailyBudget != null; }

    public boolean turnsOn() { return "ACTIVE".equals(status); }

    public static class Converter extends JsonConverters.Of<LogChange> {
        public Converter() { super(LogChange.class); }
    }
}
