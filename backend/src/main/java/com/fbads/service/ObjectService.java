package com.fbads.service;

import com.fbads.common.ApiException;
import com.fbads.common.DateRanges;
import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.dto.Responses.Insights;
import com.fbads.dto.Responses.ObjectsList;
import com.fbads.dto.Responses.ObjectsMeta;
import com.fbads.dto.Responses.Trend;
import com.fbads.dto.Responses.TrendEvent;
import com.fbads.entity.LogAction;
import com.fbads.entity.LogChange;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogError;
import com.fbads.entity.LogKind;
import com.fbads.entity.LogSnapshot;
import com.fbads.entity.LogTarget;
import com.fbads.service.facebook.FacebookActions;
import com.fbads.service.facebook.FacebookInsights;
import com.fbads.service.facebook.FacebookObjects;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Camp / nhóm QC: danh sách, số liệu theo khoảng ngày, bật/tắt và đặt ngân sách bằng tay (có ghi nhật ký). */
@Service
public class ObjectService {
    private final FacebookObjects objects;
    private final FacebookInsights insights;
    private final FacebookActions actions;
    private final SettingsService settings;
    private final LogService logs;

    public ObjectService(FacebookObjects objects, FacebookInsights insights, FacebookActions actions,
            SettingsService settings, LogService logs) {
        this.objects = objects;
        this.insights = insights;
        this.actions = actions;
        this.settings = settings;
        this.logs = logs;
    }

    public ObjectsList list(boolean refresh) {
        return new ObjectsList(objects.listObjects(refresh), objects.objectsMeta());
    }

    /** Số liệu theo khoảng ngày cho Tổng quan: ?range=last_7d hoặc ?since=…&until=… (trống = hôm nay) */
    public Insights insights(Map<String, String> q) {
        String today = DateRanges.todayIn(settings.get().getTimezone());
        DateRanges.Parsed parsed = DateRanges.parse(q, today);
        if (!parsed.ok()) throw new ApiException(400, parsed.error());
        DateRanges.Resolved range = DateRanges.resolve(parsed.spec(), today);
        FacebookInsights.RangeResult got = insights.rangeData(new FacebookInsights.RangeQuery(parsed.key(),
                DateRanges.fbParams(parsed.spec(), today), range.days()),
                "1".equals(q.get("refresh")));
        ObjectsMeta meta = objects.objectsMeta();
        return new Insights(parsed.spec().toJson(), parsed.key(), range.since(), range.until(), range.days(),
                got.at() == null || got.at() == 0 ? null : got.at(), got.stale(), meta.blockedUntil(), meta.usage(),
                meta.accountErrors(), got.data());
    }

    static final int TREND_DAYS = 30;
    private static final Pattern OBJ_ID = Pattern.compile("^[\\w-]{1,40}$");

    /**
     * Xu hướng theo ngày của một camp/nhóm QC (biểu đồ): ?days=30 (7–90), tính tới hôm nay theo múi giờ trong Cài đặt.
     * Kèm các lần bật/tắt/đổi ngân sách đã làm thật với mục này trong khoảng đó (lấy từ Nhật ký) để đánh dấu trên biểu đồ.
     */
    public Trend trend(String id, String daysParam, boolean refresh) {
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
        ZoneId zone;
        try { zone = ZoneId.of(s.getTimezone()); } catch (RuntimeException e) { zone = ZoneId.systemDefault(); }
        String until = DateRanges.todayIn(s.getTimezone());
        String since = LocalDate.parse(until).minusDays(n - 1).toString();
        FacebookInsights.TrendResult got = insights.dailyTrend(id, since, until, refresh);
        List<TrendEvent> events = new ArrayList<>();
        for (LogEntry l : logs.since(LocalDate.parse(since).atStartOfDay(zone).toInstant())) {
            if (l.getTarget() == null || !id.equals(l.getTarget().id()) || !Boolean.TRUE.equals(l.getOk())
                    || Boolean.TRUE.equals(l.getDry()) || l.getAction() == null || !l.getAction().changesObject()
                    || "mock".equals(l.getMode()) != s.isMock()) continue;
            String date = l.getTs().atZone(zone).toLocalDate().toString();
            if (date.compareTo(since) < 0 || date.compareTo(until) > 0) continue;
            events.add(new TrendEvent(l.getTs().toString(), date, l.getAction().type().code(), l.getSource(), l.getDetail()));
        }
        events.sort(Comparator.comparing(TrendEvent::ts));
        return new Trend(id, since, until, got.days(), events, got.at() == 0 ? null : got.at(), got.stale(),
                objects.objectsMeta().blockedUntil());
    }

    public void setStatus(String id, boolean on, String name) {
        manual(id, name, LogAction.status(on), on ? "Bật camp" : "Tắt camp", LogChange.status(on), () -> actions.setStatus(id, on));
    }

    public void setBudget(String id, double amount, String name) {
        long value = Math.round(amount);
        AdObject cur = objects.findCached(id);
        if (cur != null && cur.dailyBudget == null)
            throw new ApiException(400, "Mục này không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO). Hãy "
                    + "chỉnh ở cấp có ngân sách.");
        manual(id, name, LogAction.setBudget(value), "Đặt ngân sách " + Fmt.money(value), LogChange.budget(value),
                () -> actions.setBudget(id, value));
    }

    /** Thao tác tay: ghi nhật ký (kể cả khi lỗi) rồi ném lại lỗi cho giao diện */
    private void manual(String id, String name, LogAction action, String okDetail, LogChange after, Runnable fn) {
        AdObject cur = objects.findCached(id);
        String label = name == null || name.isEmpty() ? id : name;
        LogEntry e = LogEntry.of(LogKind.MANUAL, "Thủ công", label);
        e.setTarget(cur != null ? LogTarget.of(cur, label) : new LogTarget(id, label, null, null, null));
        e.setAction(action);
        e.setBefore(LogSnapshot.of(cur));
        e.setMode(settings.get().mode());
        try {
            fn.run();
        } catch (RuntimeException ex) {
            e.setDetail(ex.getMessage());
            e.setOk(false);
            e.setError(LogError.of(ex));
            logs.add(e);
            throw ex;
        }
        e.setDetail(okDetail);
        e.setOk(true);
        e.setAfter(after);
        logs.add(e);
    }
}
