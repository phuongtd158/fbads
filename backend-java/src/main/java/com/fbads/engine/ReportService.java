package com.fbads.engine;

import com.fbads.common.Fmt;
import com.fbads.engine.state.EngineState;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.FacebookService;
import com.fbads.notify.TelegramService;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Báo cáo Telegram hằng ngày (và nút Gửi báo cáo). Nhiều tài khoản → mỗi tài khoản một phần (loại tiền có thể khác nhau). */
@Service
public class ReportService {
    private final FacebookService fb;
    private final TelegramService telegram;
    private final SettingsService settings;
    private final EngineState state;
    private final EngineClock clock;

    public ReportService(FacebookService fb, TelegramService telegram, SettingsService settings, EngineState state, EngineClock clock) {
        this.fb = fb;
        this.telegram = telegram;
        this.settings = settings;
        this.state = state;
        this.clock = clock;
    }

    public TelegramService.SendResult send() {
        List<AdObject> camps = fb.listObjects(true).stream().filter(AdObject::isCampaign).toList();
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
            String head = multi ? "\n🏷 <b>" + (list.getFirst().accountName != null ? list.getFirst().accountName : list.getFirst().accountId) + "</b>\n" : "";
            parts.add(head + "Đang chạy: " + active.size() + "/" + list.size() + " camp\nChi tiêu hôm nay: " + Fmt.money(spend) + cur
                    + "\nKết quả: " + Fmt.num(results) + (results > 0 ? " | CPA " + Fmt.money(spend / results) + cur : "")
                    + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines)));
        }
        return telegram.send("📊 <b>Báo cáo Facebook Ads</b>\n" + String.join("\n", parts));
    }

    /** Tới giờ báo cáo (trễ tối đa 10 phút) và hôm nay chưa gửi → gửi */
    public void tick() {
        AppSettings s = settings.get();
        if (s.getReportTime() == null || s.getReportTime().isEmpty() || s.getTelegramToken().isEmpty()) return;
        EngineClock.Now now = clock.now();
        int at = EngineClock.toMin(s.getReportTime());
        if (now.minutes() >= at && now.minutes() - at <= ScheduleRunner.GRACE_MIN && !state.hasDaily(now.date(), "report")) {
            state.putDaily(now.date(), "report", null);
            send();
        }
    }
}
