package com.fbads.engine;

import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Giờ "địa phương" theo múi giờ trong Cài đặt (đọc lại mỗi lần gọi nên đổi múi giờ là có hiệu lực ngay).
 * Clock tiêm vào được để kiểm thử (giả lập giờ bất kỳ).
 */
@Component
public class EngineClock {
    public static final String DEFAULT_TZ = "Asia/Ho_Chi_Minh";

    /** date = 'YYYY-MM-DD', minutes = phút trong ngày, day = 0 (Chủ nhật) … 6 (Thứ bảy) */
    public record Now(String date, int minutes, int day) {}

    private final SettingsService settings;
    private volatile Clock clock = Clock.systemUTC();

    public EngineClock(SettingsService settings) { this.settings = settings; }

    public void setClock(Clock clock) { this.clock = clock; }

    public long millis() { return clock.millis(); }

    public ZoneId zone() {
        try { return ZoneId.of(settings.get().getTimezone()); } catch (RuntimeException e) { return ZoneId.of(DEFAULT_TZ); } // múi giờ sai → mặc định
    }

    public Now now() {
        ZonedDateTime t = ZonedDateTime.now(clock.withZone(zone()));
        return new Now(t.toLocalDate().toString(), t.getHour() * 60 + t.getMinute(), t.getDayOfWeek().getValue() % 7);
    }

    public static int toMin(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }
}
