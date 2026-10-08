package com.fbads.schedule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fbads.common.JsonConverters;

/** Lịch khung giờ: bật lúc on, tắt lúc off ("HH:MM"). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduleWindow(String on, String off) {
    public static class Converter extends JsonConverters.Of<ScheduleWindow> {
        public Converter() { super(ScheduleWindow.class); }
    }
}
