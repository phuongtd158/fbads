package com.fbads.service;

import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.engine.EngineClock;
import com.fbads.engine.ScheduleRunner;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogEntry;
import com.fbads.event.AppEvent;
import com.fbads.event.EventBus;
import com.fbads.service.facebook.FacebookInsights;
import com.fbads.service.facebook.FacebookObjects;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Báo cáo Telegram hằng ngày (và nút Gửi báo cáo). Nhiều tài khoản → mỗi tài khoản một phần (loại tiền có thể khác nhau).
 * Tới giờ thì engine chỉ phát sự kiện report.daily, consumer Telegram gửi. Nút "Gửi báo cáo" gửi thẳng vì giao diện cần biết ngay kết quả.
 */
@Service
public class ReportService {
    private final FacebookObjects objects;
    private final FacebookInsights insights;
    private final TelegramService telegram;
    private final SettingsService settings;
    private final EngineState state;
    private final EngineClock clock;
    private final EventBus events;
    private final LogService logs;

    public ReportService(FacebookObjects objects, FacebookInsights insights, TelegramService telegram,
                         SettingsService settings, EngineState state, EngineClock clock, EventBus events, LogService logs) {
        this.events = events;
        this.logs = logs;
        this.objects = objects;
        this.insights = insights;
        this.telegram = telegram;
        this.settings = settings;
        this.state = state;
        this.clock = clock;
    }

    /** Nút "Gửi báo cáo": gửi ngay, trả kết quả cho giao diện */
    public TelegramService.SendResult send() { return telegram.send(text()); }

    String text() {
        List<AdObject> camps = objects.listObjects(true).stream().filter(AdObject::isCampaign).toList();
        Map<String, List<AdObject>> groups = new LinkedHashMap<>();
        for (AdObject o : camps) groups.computeIfAbsent(o.accountId == null ? "" : o.accountId, k -> new ArrayList<>()).add(o);
        boolean multi = groups.size() > 1;
        List<String> parts = new ArrayList<>();
        for (List<AdObject> list : groups.values()) {
            List<AdObject> active = list.stream().filter(AdObject::isActive).toList();
            double spend = list.stream().mapToDouble(o -> o.metrics.spend()).sum();
            double results = list.stream().mapToDouble(o -> o.metrics.results()).sum();
            String cur = multi && list.getFirst().currency != null ? " " + list.getFirst().currency : "";
            List<String> lines = active.stream().limit(multi ? 10 : 15)
                    .map(o -> "• " + o.name + ": " + Fmt.money(o.metrics.spend()) + " | KQ " + Fmt.num(o.metrics.results())).toList();
            String head = multi ? "\n🏷 <b>" + (list.getFirst().accountName != null ? list.getFirst().accountName
                    : list.getFirst().accountId) + "</b>\n" : "";
            parts.add(head + "Đang chạy: " + active.size() + "/" + list.size() + " camp\nChi tiêu hôm nay: " + Fmt.money(spend) + cur
                    + "\nKết quả: " + Fmt.num(results) + (results > 0 ? " | CPA " + Fmt.money(spend / results) + cur : "")
                    + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines)));
        }
        return "📊 <b>Báo cáo Facebook Ads</b>\n" + String.join("\n", parts);
    }

    /** Tới giờ báo cáo (trễ tối đa 10 phút) và hôm nay chưa gửi → gửi. Thứ Hai gửi thêm báo cáo tuần (nếu bật). */
    public void tick() {
        AppSettings s = settings.get();
        if (s.getReportTime() == null || s.getReportTime().isEmpty() || s.getTelegramToken().isEmpty()) return;
        EngineClock.Now now = clock.now();
        int at = EngineClock.toMin(s.getReportTime());
        if (now.minutes() < at || now.minutes() - at > ScheduleRunner.GRACE_MIN) return;
        if (!state.hasDaily(now.date(), "report")) {
            state.putDaily(now.date(), "report", null);
            events.publish(AppEvent.DAILY_REPORT, "report", true, Map.of("text", text()));
        }
        if (s.isWeeklyReport() && now.day() == 1 && !state.hasDaily(now.date(), "weekly")) { // thứ Hai
            state.putDaily(now.date(), "weekly", null);
            events.publish(AppEvent.TELEGRAM_TEXT, "report", true, Map.of("text", weeklyText(now)));
        }
    }

    // ------------------------------------------------------------------ Báo cáo tuần
    /** Tuần trước (thứ Hai → Chủ nhật) và tuần liền trước đó */
    public record Week(String since, String until, String prevSince, String prevUntil) {}

    public static Week lastWeek(EngineClock.Now now) {
        LocalDate until = LocalDate.parse(now.date()).minusDays((now.day() + 6) % 7 + 1); // Chủ nhật gần nhất đã qua
        return new Week(until.minusDays(6).toString(), until.toString(), until.minusDays(13).toString(), until.minusDays(7).toString());
    }

    private Map<String, Metrics> weekData(String since, String until) {
        return insights.rangeData(new FacebookInsights.RangeQuery("r:" + since + "_" + until,
                Map.of("time_range", "{\"since\":\"" + since + "\",\"until\":\"" + until + "\"}"), 7), false).data();
    }

    private static String change(double a, double b) { return b > 0 ? (a >= b ? "+" : "") + Math.round((a / b - 1) * 100) + "%" : ""; }

    private static String esc(String t) { return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static String dm(String iso) { return iso.substring(8) + "/" + iso.substring(5, 7); }

    public record Row(AdObject o, Metrics m) {}

    public record Ranked(List<Row> best, List<Row> worst) {}

    /** Camp tốt nhất: CPA thấp nhất (có kết quả). Tệ nhất: chi tiêu mà không có kết quả, rồi tới CPA cao nhất. */
    public static Ranked rankCamps(List<Row> rows) {
        List<Row> spent = rows.stream().filter(r -> r.m().spend() > 0).toList();
        List<Row> best = spent.stream()
                .filter(r -> r.m().results() > 0)
                .sorted(Comparator.comparingDouble(r -> cpaOf(r.m())))
                .limit(3).toList();
        ToDoubleFunction<Row> cost = r -> r.m().results() > 0 ? cpaOf(r.m()) : Double.POSITIVE_INFINITY;
        List<Row> worst = spent.stream().filter(r -> !best.contains(r))
                .sorted((a, b) -> {
                    double ca = cost.applyAsDouble(a), cb = cost.applyAsDouble(b);
                    return ca == cb ? Double.compare(b.m().spend(), a.m().spend()) : Double.compare(cb, ca);
                }).limit(3).toList();
        return new Ranked(best, worst);
    }

    private static double cpaOf(Metrics m) { return m.cpa() != null ? m.cpa() : m.spend() / m.results(); }

    /** Số lần tool tự thao tác thật trong tuần (lịch, rule, dừng khẩn), không tính chạy thử và thao tác tay */
    private Map<String, Integer> toolActions(String since, String until) {
        boolean mock = settings.get().isMock();
        Map<String, Integer> n = new LinkedHashMap<>(Map.of("on", 0, "off", 0, "budget", 0));
        ZoneId zone = clock.zone();
        for (LogEntry l : logs.since(LocalDate.parse(since).atStartOfDay(zone).toInstant())) {
            Object type = l.getAction() == null ? null : l.getAction().get("type");
            if (!List.of("rule", "schedule", "system").contains(l.getKind()) || !Boolean.TRUE.equals(l.getOk())
                    || Boolean.TRUE.equals(l.getDry())
                    || "mock".equals(l.getMode()) != mock || !(type instanceof String t) || !n.containsKey(t)) continue;
            String d = l.getTs().atZone(zone).toLocalDate().toString();
            if (d.compareTo(since) >= 0 && d.compareTo(until) <= 0) n.merge(t, 1, Integer::sum);
        }
        return n;
    }

    private record Sum(double spend, double results, double revenue) {}

    private static Sum sum(List<AdObject> list, Map<String, Metrics> data) {
        double sp = 0, rs = 0, rv = 0;
        for (AdObject o : list) {
            Metrics m = data.get(o.id);
            if (m != null) { sp += m.spend(); rs += m.results(); rv += m.revenue(); }
        }
        return new Sum(sp, rs, rv);
    }

    String weeklyText(EngineClock.Now now) {
        Week w = lastWeek(now);
        List<AdObject> camps = objects.listObjects(false).stream().filter(AdObject::isCampaign).toList();
        Map<String, Metrics> cur = weekData(w.since(), w.until()), prev = weekData(w.prevSince(), w.prevUntil());
        Map<String, List<AdObject>> groups = new LinkedHashMap<>();
        for (AdObject o : camps) groups.computeIfAbsent(o.accountId == null ? "" : o.accountId, k -> new ArrayList<>()).add(o);
        boolean multi = groups.size() > 1;
        List<String> parts = new ArrayList<>();
        for (List<AdObject> list : groups.values()) {
            Sum c = sum(list, cur), p = sum(list, prev);
            String unit = list.getFirst().currency != null
                    && !list.getFirst().currency.isEmpty() ? " " + esc(list.getFirst().currency) : "";
            Double cpa = c.results() > 0 ? c.spend() / c.results() : null, pcpa = p.results() > 0 ? p.spend() / p.results() : null;
            List<String> lines = new ArrayList<>();
            lines.add("Chi tiêu: <b>" + Fmt.money(c.spend()) + unit + "</b>"
                    + (p.spend() > 0 ? " (" + change(c.spend(), p.spend()) + " so với tuần trước)" : ""));
            lines.add("Kết quả: <b>" + Fmt.num(c.results()) + "</b>"
                    + (p.results() > 0 ? " (" + change(c.results(), p.results()) + ")" : ""));
            if (cpa != null)
                lines.add("CPA: <b>" + Fmt.money(cpa) + unit + "</b>" + (pcpa != null && pcpa > 0 ? " (" + change(cpa, pcpa) + ")" : ""));
            if (c.revenue() > 0 && c.spend() > 0)
                lines.add("ROAS: <b>" + Fmt.fixed2(c.revenue() / c.spend()) + "</b>"
                        + (p.revenue() > 0 && p.spend() > 0 ? " (tuần trước " + Fmt.fixed2(p.revenue() / p.spend()) + ")" : ""));
            Ranked r = rankCamps(list.stream().filter(o -> cur.containsKey(o.id)).map(o -> new Row(o, cur.get(o.id))).toList());
            Function<Row, String> row = x -> "• " + esc(x.o().name) + ": "
                    + (x.m().results() > 0 ? "CPA " + Fmt.money(cpaOf(x.m())) + ", " + Fmt.num(x.m().results()) + " KQ"
                            : "chi " + Fmt.money(x.m().spend()) + ", chưa có KQ");
            if (!r.best().isEmpty()) lines.add("👍 Tốt nhất:\n" + String.join("\n", r.best().stream().map(row).toList()));
            if (!r.worst().isEmpty()) lines.add("👎 Cần xem lại:\n" + String.join("\n", r.worst().stream().map(row).toList()));
            AdObject f = list.getFirst();
            parts.add((multi ? "\n🏷 <b>" + esc(f.accountName != null ? f.accountName : f.accountId) + "</b>\n" : "")
                    + String.join("\n", lines));
        }
        Map<String, Integer> n = toolActions(w.since(), w.until());
        int total = n.get("on") + n.get("off") + n.get("budget");
        String acts = total > 0 ? "\n\n🤖 Tool đã tự thao tác " + total + " lần: bật " + n.get("on") + ", tắt " + n.get("off")
                + ", đổi ngân sách " + n.get("budget") + "."
                : "\n\n🤖 Tuần qua tool không tự thao tác lần nào.";
        String body = groups.isEmpty() ? "Chưa có camp nào." : String.join("\n", parts);
        return "🗓 <b>Báo cáo tuần " + dm(w.since()) + " – " + dm(w.until()) + "</b>\n" + body + acts;
    }

    /** Nút "Gửi báo cáo tuần": gửi ngay, trả kết quả cho giao diện */
    public TelegramService.SendResult sendWeekly() { return telegram.send(weeklyText(clock.now())); }
}
