package com.fbads.validation;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Các kiểm tra nhỏ dùng chung (bản Java của shared/validate.mjs). Trả chuỗi rỗng = hợp lệ. */
public final class Checks {
    public static final double BUDGET_MAX = 1e10;
    public static final int NAME_MAX = 80;
    public static final int SCHEDULE_SET_PCT_MAX = 300;
    public static final int RULE_PCT_INCREASE_MAX = 100;
    public static final int RULE_PCT_DECREASE_MAX = 90;
    public static final int BIG_PCT_WARN = 30;
    public static final int COOLDOWN_MAX = 168;
    public static final int INTERVAL_MIN = 5, INTERVAL_MAX = 1440;
    public static final int CAP_PCT_MIN = 5, CAP_PCT_MAX = 100;
    public static final int SCHEDULE_TIMES_MAX = 24;
    public static final int ACCOUNTS_MAX = 20;
    public static final int MAX_TG_CHATS = 10;

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern TG_TOKEN = Pattern.compile("^\\d{6,}:[A-Za-z0-9_-]{30,}$");
    private static final Pattern TG_CHAT = Pattern.compile("^(-?\\d{5,}|@[A-Za-z0-9_]{5,})$");

    private Checks() {}

    public static boolean isTime(String s) { return s != null && TIME.matcher(s).matches(); }

    public static boolean isTimezone(String tz) {
        if (tz == null || tz.isBlank()) return false;
        try { ZoneId.of(tz); return true; } catch (RuntimeException e) { return false; }
    }

    public static String checkToken(String t) {
        String s = t == null ? "" : t.trim();
        if (s.isEmpty()) return "Hãy dán Access Token";
        if (s.matches("(?s).*\\s.*")) return "Token không được chứa khoảng trắng hay xuống dòng";
        if (s.length() < 20) return "Token quá ngắn, hãy sao chép đầy đủ (thường bắt đầu bằng EAA…)";
        return "";
    }

    public static String cleanAccountId(String v) { return (v == null ? "" : v).trim().replaceFirst("(?i)^act_", ""); }

    public static String checkAccountId(String v) {
        return cleanAccountId(v).matches("^\\d{5,}$") ? "" : "ID tài khoản quảng cáo chỉ gồm chữ số và có ít nhất 5 số (ví dụ 1234567890)";
    }

    public static String checkAppId(String v) { return (v == null ? "" : v).trim().matches("^\\d{8,}$") ? "" : "App ID chỉ gồm chữ số (ít nhất 8 số)"; }

    public static String checkAppSecret(String v) { return (v == null ? "" : v).trim().matches("(?i)^[a-f0-9]{16,}$") ? "" : "App Secret gồm chữ và số (thường 32 ký tự)"; }

    public static String checkConfigId(String v) { return v == null || v.isEmpty() || v.trim().matches("^\\d{5,}$") ? "" : "Configuration ID chỉ gồm chữ số"; }

    public static String checkTelegramToken(String v) {
        return v == null || v.isEmpty() || TG_TOKEN.matcher(v.trim()).matches() ? "" : "Bot Token không đúng dạng (ví dụ 123456789:AAxxxxxxxx…)";
    }

    /** Nhiều Chat ID cách nhau bằng dấu phẩy / chấm phẩy / khoảng trắng; bỏ trùng không phân biệt hoa thường */
    public static List<String> parseChatIds(String v) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String raw : (v == null ? "" : v).split("[\\s,;]+")) {
            String id = raw.trim();
            if (!id.isEmpty()) seen.putIfAbsent(id.toLowerCase(), id);
        }
        return List.copyOf(seen.values());
    }

    public static String checkTelegramChats(String v) {
        List<String> ids = parseChatIds(v);
        for (String id : ids) {
            if (!TG_CHAT.matcher(id).matches())
                return "“" + (id.length() > 24 ? id.substring(0, 24) + "…" : id) + "” không phải Chat ID hợp lệ (dãy số, có thể có dấu -, hoặc @tenkenh)";
        }
        if (ids.size() > MAX_TG_CHATS) return "Tối đa " + MAX_TG_CHATS + " Chat ID (đang nhập " + ids.size() + ")";
        return "";
    }
}
