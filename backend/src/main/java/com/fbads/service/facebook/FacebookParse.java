package com.fbads.service.facebook;

import com.fbads.ads.Metrics;
import com.fbads.service.facebook.GraphData.ActionStat;
import com.fbads.service.facebook.GraphData.InsightRow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Đọc số trong câu trả lời của Facebook: số liệu Insights, giờ, tiền, trạng thái tài khoản. Không gọi mạng. */
public final class FacebookParse {
    private static final Set<String> NO_DECIMAL = Set.of("VND", "JPY", "KRW", "CLP", "ISK", "PYG");
    private static final Map<String, List<String>> RESULT_ALIASES = Map.of(
            "purchase", List.of("omni_purchase", "purchase", "onsite_conversion.purchase",
                    "offsite_conversion.fb_pixel_purchase", "onsite_web_purchase"),
            "lead", List.of("lead", "onsite_conversion.lead_grouped", "offsite_conversion.fb_pixel_lead", "onsite_web_lead"),
            "initiate_checkout", List.of("omni_initiated_checkout", "initiate_checkout",
                    "onsite_conversion.initiate_checkout", "offsite_conversion.fb_pixel_initiate_checkout"));
    static final Map<Integer, String> ACC_STATUS = Map.of(1, "Đang hoạt động", 2, "Bị vô hiệu hoá", 3,
            "Nợ thanh toán", 7, "Đang xét duyệt rủi ro", 8, "Đang xử lý thanh toán", 9,
            "Trong thời gian gia hạn", 100, "Đang chờ đóng", 101, "Đã đóng", 201, "Đang chờ", 202, "Đã đóng");
    static final String INSIGHT_FIELDS = "spend,impressions,reach,clicks,actions,action_values";

    private FacebookParse() {}

    /** Facebook tính ngân sách theo đơn vị nhỏ nhất (cent), trừ các tiền tệ không có số lẻ như VND */
    static double offsetOf(String cur) {
        return NO_DECIMAL.contains(cur) ? 1 : 100;
    }

    /** Giá trị của loại hành động đầu tiên (theo thứ tự names) có trong danh sách; không có → 0 */
    private static double pick(List<ActionStat> stats, List<String> names) {
        if (stats == null) return 0;
        for (String n : names) {
            for (ActionStat a : stats) {
                if (n.equals(a.actionType())) return parseNum(a.value());
            }
        }
        return 0;
    }

    /** Số Facebook gửi dạng chuỗi ("12.5"); thiếu hoặc sai dạng → 0 */
    static double parseNum(String v) {
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Một dòng Insights → số liệu. Loại "kết quả" có nhiều tên tuỳ nơi phát sinh: lấy tên ĐẦU TIÊN có trong số liệu
     * (không cộng dồn).
     */
    public static Metrics metricsFrom(InsightRow row, String resultAction) {
        double spend = parseNum(row.spend());
        List<String> names = RESULT_ALIASES.getOrDefault(resultAction, List.of(resultAction));
        double results = pick(row.actions(), names), value = pick(row.actionValues(), names);
        return new Metrics(spend, (long) parseNum(row.impressions()), (long) parseNum(row.reach()),
                (long) parseNum(row.clicks()), results,
                results > 0 ? spend / results : null, value, spend > 0 ? value / spend : null,
                pick(row.actions(), List.of("onsite_conversion.messaging_conversation_started_7d")),
                pick(row.actions(), RESULT_ALIASES.get("initiate_checkout")),
                pick(row.actions(), RESULT_ALIASES.get("lead")),
                pick(row.actions(), List.of("onsite_conversion.lead_grouped")),
                pick(row.actions(), List.of("comment")));
    }

    /** Ngân sách Facebook gửi (đơn vị nhỏ nhất, dạng chuỗi) → số tiền; không có → null (không có ngân sách riêng) */
    static Double budget(String raw, String currency) {
        return raw == null || raw.isEmpty() ? null : Long.parseLong(raw) / offsetOf(currency);
    }

    /** Giờ dạng "2026-09-01T10:00:00+0700" → mili giây; không có / sai dạng → null */
    static Long ms(String t) {
        if (t == null) return null;
        try {
            return OffsetDateTime.parse(t.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant()
                    .toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
