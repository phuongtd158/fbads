package com.fbads.service;

import com.fbads.client.FbException;
import com.fbads.common.ApiException;
import com.fbads.common.DateRanges;
import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.entity.LogEntry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Camp / nhóm QC: danh sách, số liệu theo khoảng ngày, bật/tắt và đặt ngân sách bằng tay (có ghi nhật ký). */
@Service
public class ObjectService {
    private final FacebookService fb;
    private final SettingsService settings;
    private final LogService logs;

    public ObjectService(FacebookService fb, SettingsService settings, LogService logs) {
        this.fb = fb;
        this.settings = settings;
        this.logs = logs;
    }

    public Map<String, Object> list(boolean refresh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", fb.listObjects(refresh));
        m.putAll(fb.objectsMeta());
        return m;
    }

    /** Số liệu theo khoảng ngày cho Tổng quan: ?range=last_7d hoặc ?since=…&until=… (trống = hôm nay) */
    public Map<String, Object> insights(Map<String, String> q) {
        String today = DateRanges.todayIn(settings.get().getTimezone());
        DateRanges.Parsed parsed = DateRanges.parse(q, today);
        if (!parsed.ok()) throw new ApiException(400, parsed.error());
        DateRanges.Resolved range = DateRanges.resolve(parsed.spec(), today);
        FacebookService.RangeResult got = fb.rangeData(new FacebookService.RangeQuery(parsed.key(), DateRanges.fbParams(parsed.spec(), today), range.days()),
                "1".equals(q.get("refresh")));
        Map<String, Object> meta = fb.objectsMeta();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", parsed.spec().toJson());
        m.put("key", parsed.key());
        m.put("since", range.since());
        m.put("until", range.until());
        m.put("days", range.days());
        m.put("at", got.at() == null || got.at() == 0 ? null : got.at());
        m.put("stale", got.stale());
        m.put("blockedUntil", meta.get("blockedUntil"));
        m.put("usage", meta.get("usage"));
        m.put("accountErrors", meta.get("accountErrors"));
        m.put("metrics", got.data());
        return m;
    }

    static final int TREND_DAYS = 30;
    private static final java.util.regex.Pattern OBJ_ID = java.util.regex.Pattern.compile("^[\\w-]{1,40}$");

    /**
     * Xu hướng theo ngày của một camp/nhóm QC (biểu đồ): ?days=30 (7–90), tính tới hôm nay theo múi giờ trong Cài đặt.
     * Kèm các lần bật/tắt/đổi ngân sách đã làm thật với mục này trong khoảng đó (lấy từ Nhật ký) để đánh dấu trên biểu đồ.
     */
    public Map<String, Object> trend(String id, String daysParam, boolean refresh) {
        if (!OBJ_ID.matcher(id).matches()) throw new ApiException(400, "Mã camp không hợp lệ");
        int n;
        try {
            double d = daysParam == null ? TREND_DAYS : Double.parseDouble(daysParam.trim().isEmpty() ? "0" : daysParam);
            if (d != Math.rint(d) || d < 7 || d > 90) throw new NumberFormatException();
            n = (int) d;
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Số ngày phải từ 7 đến 90");
        }
        var s = settings.get();
        java.time.ZoneId zone;
        try { zone = java.time.ZoneId.of(s.getTimezone()); } catch (RuntimeException e) { zone = java.time.ZoneId.systemDefault(); }
        String until = DateRanges.todayIn(s.getTimezone());
        String since = java.time.LocalDate.parse(until).minusDays(n - 1).toString();
        FacebookService.TrendResult got = fb.dailyTrend(id, since, until, refresh);
        java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (LogEntry l : logs.since(java.time.LocalDate.parse(since).atStartOfDay(zone).toInstant())) {
            Object type = l.getAction() == null ? null : l.getAction().get("type");
            if (l.getTarget() == null || !id.equals(l.getTarget().get("id")) || !Boolean.TRUE.equals(l.getOk()) || Boolean.TRUE.equals(l.getDry())
                    || !java.util.List.of("on", "off", "budget").contains(type) || "mock".equals(l.getMode()) != s.isMock()) continue;
            String date = l.getTs().atZone(zone).toLocalDate().toString();
            if (date.compareTo(since) < 0 || date.compareTo(until) > 0) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ts", l.getTs().toString()); e.put("date", date); e.put("type", type); e.put("source", l.getSource()); e.put("detail", l.getDetail());
            events.add(e);
        }
        events.sort(java.util.Comparator.comparing(e -> (String) e.get("ts")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("since", since); m.put("until", until); m.put("days", got.days()); m.put("events", events);
        m.put("at", got.at() == 0 ? null : got.at()); m.put("stale", got.stale()); m.put("blockedUntil", fb.objectsMeta().get("blockedUntil"));
        return m;
    }

    public void setStatus(String id, boolean on, String name) {
        manual(id, name, Map.of("type", on ? "on" : "off"), on ? "Bật camp" : "Tắt camp", Map.of("status", on ? "ACTIVE" : "PAUSED"), () -> fb.setStatus(id, on));
    }

    public void setBudget(String id, double amount, String name) {
        long value = Math.round(amount);
        AdObject cur = fb.findCached(id);
        if (cur != null && cur.dailyBudget == null)
            throw new ApiException(400, "Mục này không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO). Hãy chỉnh ở cấp có ngân sách.");
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "budget"); action.put("mode", "set"); action.put("value", value);
        manual(id, name, action, "Đặt ngân sách " + Fmt.money(value), Map.of("dailyBudget", value), () -> fb.setBudget(id, value));
    }

    /** Thao tác tay: ghi nhật ký (kể cả khi lỗi) rồi ném lại lỗi cho giao diện */
    private void manual(String id, String name, Map<String, Object> action, String okDetail, Map<String, Object> after, Runnable fn) {
        AdObject cur = fb.findCached(id);
        String label = name == null || name.isEmpty() ? id : name;
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", id);
        target.put("name", label);
        if (cur != null) {
            target.put("level", cur.level);
            if (cur.accountId != null) { target.put("accountId", cur.accountId); target.put("accountName", cur.accountName); }
        }
        Map<String, Object> before = FacebookService.snapshot(cur);
        String mode = settings.get().mode();
        Consumer<LogEntry> base = e -> {
            e.setKind("manual"); e.setSource("Thủ công"); e.setName(label); e.setTarget(target); e.setAction(action); e.setBefore(before); e.setMode(mode);
        };
        try {
            fn.run();
            logs.log(e -> { base.accept(e); e.setDetail(okDetail); e.setOk(true); e.setAfter(after); });
        } catch (RuntimeException ex) {
            logs.log(e -> { base.accept(e); e.setDetail(ex.getMessage()); e.setOk(false); e.setError(FbException.describe(ex)); });
            throw ex;
        }
    }
}
