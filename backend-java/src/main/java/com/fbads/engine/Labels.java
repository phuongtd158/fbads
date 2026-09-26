package com.fbads.engine;

import com.fbads.common.Fmt;

import java.util.Map;

/** Nhãn và cách hiện số liệu trong câu chữ của engine (nhật ký, Telegram) */
public final class Labels {
    public static final Map<String, String> METRIC = Map.ofEntries(
            Map.entry("cpa", "CPA"), Map.entry("roas", "ROAS"), Map.entry("spend", "Chi tiêu"), Map.entry("results", "Kết quả"),
            Map.entry("ctr", "CTR"), Map.entry("cpc", "CPC"), Map.entry("cpm", "CPM"), Map.entry("messages", "Tin nhắn"),
            Map.entry("costPerMessage", "Chi phí/tin nhắn"), Map.entry("leads", "Lead"), Map.entry("costPerLead", "Chi phí/lead"),
            Map.entry("frequency", "Tần suất"));
    public static final Map<String, String> RANGE = Map.of("today", "hôm nay", "yesterday", "hôm qua", "last_3d", "3 ngày gần nhất", "last_7d", "7 ngày gần nhất");

    private Labels() {}

    public static String metric(String m) { return METRIC.getOrDefault(m, String.valueOf(m)); }

    public static String range(String r) { return RANGE.getOrDefault(r, String.valueOf(r)); }

    /** Giá trị số liệu để hiện: ∞ khi chi tiêu mà chưa có kết quả */
    public static String show(double v, String metric) {
        if (v == Double.POSITIVE_INFINITY) return "∞ (chưa có kết quả)";
        if ("roas".equals(metric) || "frequency".equals(metric)) return Fmt.fixed2(v);
        if ("ctr".equals(metric)) return Fmt.fixed2(v) + "%";
        return Fmt.money(v);
    }
}
