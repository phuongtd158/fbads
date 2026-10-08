package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fbads.common.JsonConverters;

/** Nhật ký: dòng này đã được hoàn tác lúc nào (at, dạng ISO), bởi dòng nhật ký nào (logId). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogUndone(String at, String logId) {
    public static LogUndone by(LogEntry undo) { return new LogUndone(undo.getTs().toString(), undo.getId()); }

    public static class Converter extends JsonConverters.Of<LogUndone> {
        public Converter() { super(LogUndone.class); }
    }
}
