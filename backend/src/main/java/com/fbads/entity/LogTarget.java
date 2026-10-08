package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.JsonConverters;
import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;

/** Nhật ký: thao tác lên camp/nhóm nào. Lịch không tìm thấy camp thì chỉ có id. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogTarget(String id, String name, AdLevel level, String accountId, String accountName) {
    public static LogTarget of(AdObject o) { return of(o, o.name()); }

    /** Như of(o) nhưng ghi tên khác (thao tác tay ghi tên người dùng thấy trên màn hình) */
    public static LogTarget of(AdObject o, String name) {
        return new LogTarget(o.id(), name, o.level(), o.accountId(), o.accountId() == null ? null : o.accountName());
    }

    public static LogTarget idOnly(String id) { return new LogTarget(id, null, null, null, null); }

    public static class Converter extends JsonConverters.Of<LogTarget> {
        public Converter() { super(LogTarget.class); }
    }
}
