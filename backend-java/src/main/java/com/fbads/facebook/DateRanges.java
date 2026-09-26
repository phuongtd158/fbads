package com.fbads.facebook;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Khoảng ngày cho số liệu ở Tổng quan, giống bộ chọn ngày của Ads Manager (bản Java của shared/dates.mjs).
 * Làm việc với ngày 'YYYY-MM-DD' (LocalDate) nên không bị lệch múi giờ.
 */
public final class DateRanges {
    public static final int MAX_BACK_MONTHS = 37;
    /** id → tên date_preset của Facebook (null = gửi time_range theo ngày đã tính) */
    private static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        for (String p : List.of("today", "yesterday", "last_3d", "last_7d", "last_14d", "last_28d", "last_30d", "this_week_mon_today",
                "last_week_mon_sun", "this_month", "last_month", "maximum")) PRESETS.put(p, p);
        PRESETS.put("today_yesterday", null);
    }

    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

    /** spec: preset hoặc since/until */
    public record Spec(String preset, String since, String until) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (preset != null) m.put("preset", preset);
            else { m.put("since", since); m.put("until", until); }
            return m;
        }
    }

    public record Parsed(boolean ok, String error, Spec spec, String key) {}

    public record Resolved(String since, String until, Integer days) {}

    private DateRanges() {}

    public static boolean isIsoDate(String s) {
        if (s == null || !ISO.matcher(s).matches()) return false;
        try { LocalDate.parse(s); return true; } catch (DateTimeParseException e) { return false; }
    }

    /** Hôm nay theo múi giờ tz; múi giờ sai → giờ máy */
    public static String todayIn(String tz) {
        try { return LocalDate.now(ZoneId.of(tz)).toString(); } catch (RuntimeException e) { return LocalDate.now().toString(); }
    }

    public static String minDate(String today) {
        return LocalDate.parse(today).minusMonths(MAX_BACK_MONTHS).withDayOfMonth(1).toString();
    }

    public static String specKey(Spec s) { return s.preset() != null ? "p:" + s.preset() : "r:" + s.since() + "_" + s.until(); }

    public static Resolved resolve(Spec spec, String todayIso) {
        LocalDate today = LocalDate.parse(todayIso);
        if (spec.preset() != null) {
            LocalDate since, until;
            int dow = today.getDayOfWeek().getValue() - 1; // Thứ hai = 0
            switch (spec.preset()) {
                case "today" -> since = until = today;
                case "yesterday" -> since = until = today.minusDays(1);
                case "today_yesterday" -> { since = today.minusDays(1); until = today; }
                case "this_week_mon_today" -> { since = today.minusDays(dow); until = today; }
                case "last_week_mon_sun" -> { LocalDate mon = today.minusDays(dow); since = mon.minusDays(7); until = mon.minusDays(1); }
                case "this_month" -> { since = today.withDayOfMonth(1); until = today; }
                case "last_month" -> { until = today.withDayOfMonth(1).minusDays(1); since = until.withDayOfMonth(1); }
                case "maximum" -> { return new Resolved(null, todayIso, null); }
                default -> {
                    Matcher m = Pattern.compile("^last_(\\d+)d$").matcher(spec.preset());
                    if (!m.matches()) throw new IllegalArgumentException("Khoảng ngày không hợp lệ: " + spec.preset());
                    since = today.minusDays(Long.parseLong(m.group(1)));
                    until = today.minusDays(1);
                }
            }
            return new Resolved(since.toString(), until.toString(), (int) ChronoUnit.DAYS.between(since, until) + 1);
        }
        return new Resolved(spec.since(), spec.until(), (int) ChronoUnit.DAYS.between(LocalDate.parse(spec.since()), LocalDate.parse(spec.until())) + 1);
    }

    /** Tham số gửi Facebook Insights: date_preset khi có tên sẵn, không thì time_range */
    public static Map<String, String> fbParams(Spec spec, String today) {
        if (spec.preset() != null && PRESETS.get(spec.preset()) != null) return Map.of("date_preset", PRESETS.get(spec.preset()));
        Resolved r = resolve(spec, today);
        return Map.of("time_range", "{\"since\":\"" + r.since() + "\",\"until\":\"" + r.until() + "\"}");
    }

    /** Kiểm tra tham số người dùng gửi: range/preset hoặc since+until; trống → hôm nay */
    public static Parsed parse(Map<String, String> q, String today) {
        String preset = q.get("range") != null ? q.get("range") : q.get("preset");
        if (q.get("since") != null || q.get("until") != null) {
            String since = q.get("since"), until = q.get("until");
            if (!isIsoDate(since) || !isIsoDate(until)) return err("Ngày bắt đầu và ngày kết thúc phải có dạng năm-tháng-ngày (vd 2026-09-01).");
            if (since.compareTo(until) > 0) return err("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
            if (until.compareTo(today) > 0) return err("Không chọn được ngày trong tương lai.");
            if (since.compareTo(minDate(today)) < 0) return err("Facebook chỉ giữ số liệu khoảng " + MAX_BACK_MONTHS + " tháng gần nhất.");
            Spec s = new Spec(null, since, until);
            return new Parsed(true, null, s, specKey(s));
        }
        if (preset == null || preset.isEmpty()) { Spec s = new Spec("today", null, null); return new Parsed(true, null, s, specKey(s)); }
        if (!PRESETS.containsKey(preset)) return err("Khoảng ngày “" + preset + "” không được hỗ trợ.");
        Spec s = new Spec(preset, null, null);
        return new Parsed(true, null, s, specKey(s));
    }

    private static Parsed err(String m) { return new Parsed(false, m, null, null); }
}
