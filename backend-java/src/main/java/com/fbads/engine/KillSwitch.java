package com.fbads.engine;

import com.fbads.common.Fmt;
import com.fbads.engine.state.EngineState;
import com.fbads.facebook.AdObject;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dừng khẩn: tổng chi tiêu hôm nay ≥ mức đặt trước thì tắt mọi camp đang chạy (mỗi ngày tối đa một lần).
 * Phạm vi "total" = tổng mọi tài khoản; "account" = từng tài khoản một mức riêng (chưa đặt thì dùng mức chung).
 */
@Service
public class KillSwitch {
    private final SettingsService settings;
    private final EngineState state;
    private final EngineClock clock;
    private final ActionExecutor executor;

    public KillSwitch(SettingsService settings, EngineState state, EngineClock clock, ActionExecutor executor) {
        this.settings = settings;
        this.state = state;
        this.clock = clock;
        this.executor = executor;
    }

    private static Map<String, Object> condition(double limit, double spend) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("metric", "spend"); c.put("op", ">"); c.put("range", "today"); c.put("threshold", limit); c.put("actual", spend);
        c.put("minSpend", 0); c.put("spend", spend);
        return c;
    }

    public boolean check(List<AdObject> objs) {
        AppSettings s = settings.get();
        if (!s.isKillSwitchEnabled()) return false;
        double globalLimit = s.getDailySpendLimit();
        String today = clock.now().date();
        List<AdObject> camps = objs.stream().filter(AdObject::isCampaign).toList();
        boolean dry = s.isDry();

        if ("account".equals(s.getKillScope())) {
            Map<String, List<AdObject>> byAcc = new LinkedHashMap<>();
            for (AdObject o : camps) byAcc.computeIfAbsent(o.accountId == null ? "" : o.accountId, k -> new ArrayList<>()).add(o);
            boolean fired = false;
            for (var entry : byAcc.entrySet()) {
                String id = entry.getKey();
                List<AdObject> list = entry.getValue();
                double own = s.target(id, "dailySpendLimit");
                double limit = own > 0 ? own : globalLimit;
                if (limit <= 0 || state.hasDaily(today, "kill:acc:" + id)) continue;
                double spend = list.stream().mapToDouble(o -> o.metrics.spend()).sum();
                if (spend < limit) continue;
                state.putDaily(today, "kill:acc:" + id, null);
                List<AdObject> active = list.stream().filter(AdObject::isActive).toList();
                String cur = list.getFirst().currency != null ? " " + list.getFirst().currency : "";
                String accName = list.getFirst().accountName != null ? list.getFirst().accountName : id;
                executor.record(false, e -> {
                    e.setKind("system"); e.setSource("Dừng khẩn"); e.setName("Tài khoản " + accName); e.setOk(true); e.setDry(dry); e.setMode(s.mode());
                    e.setDetail("Chi tiêu " + Fmt.money(spend) + cur + " đã vượt mức " + Fmt.money(limit) + cur + " của tài khoản này — "
                            + (dry ? "(chạy thử) sẽ tắt" : "tắt") + " " + active.size() + " camp đang chạy (các tài khoản khác không bị ảnh hưởng).");
                    e.setCondition(condition(limit, spend));
                });
                for (AdObject o : active) executor.act(o, Action.off(), "Dừng khẩn", new ActCtx("system", null, null, null, true));
                fired = true;
            }
            return fired;
        }

        if (globalLimit <= 0 || state.hasDaily(today, "kill:total")) return false;
        double spend = camps.stream().mapToDouble(o -> o.metrics.spend()).sum();
        if (spend < globalLimit) return false;
        state.putDaily(today, "kill:total", null);
        List<AdObject> active = camps.stream().filter(AdObject::isActive).toList();
        executor.record(false, e -> {
            e.setKind("system"); e.setSource("Dừng khẩn"); e.setName("Tổng chi tiêu hôm nay"); e.setOk(true); e.setDry(dry); e.setMode(s.mode());
            e.setDetail("Chi tiêu " + Fmt.money(spend) + " đã vượt mức " + Fmt.money(globalLimit) + " — " + (dry ? "(chạy thử) sẽ tắt" : "tắt") + " "
                    + active.size() + " camp đang chạy.");
            e.setCondition(condition(globalLimit, spend));
        });
        for (AdObject o : active) executor.act(o, Action.off(), "Dừng khẩn", new ActCtx("system", null, null, null, true));
        return true;
    }
}
