package com.fbads.facebook;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dữ liệu giả để dùng thử (chế độ mock): 6 chiến dịch trên 2 tài khoản mẫu, chi tiêu tăng dần theo giờ trong ngày. */
public class MockAds {
    public record Account(String accountId, String accountName, String currency) {}

    public static final List<Account> ACCOUNTS = List.of(
            new Account("mock_a", "Tài khoản mẫu A", "VND"),
            new Account("mock_b", "Tài khoản mẫu B", "VND"));

    private static final String[] NAMES = {"[Sale] Mua hàng - Khách lạnh", "[Retarget] Người xem 7 ngày", "[Lead] Form đăng ký tư vấn",
            "[Sale] Lookalike 1%", "[Brand] Video giới thiệu", "[Sale] Combo cuối tuần"};
    private static final double[] BUDGETS = {500000, 300000, 200000, 800000, 150000, 400000};

    private List<AdObject> objs;

    private synchronized List<AdObject> objs() {
        if (objs == null) {
            objs = new ArrayList<>();
            for (int i = 0; i < NAMES.length; i++) {
                AdObject o = new AdObject();
                o.id = "mock_" + (i + 1);
                o.name = NAMES[i];
                o.level = "campaign";
                o.status = o.effective = i % 3 == 2 ? "PAUSED" : "ACTIVE";
                o.dailyBudget = BUDGETS[i];
                o.seed = i + 1;
                o.learning = i == 4;
                Account a = i < 4 ? ACCOUNTS.get(0) : ACCOUNTS.get(1);
                o.accountId = a.accountId();
                o.accountName = a.accountName();
                o.currency = a.currency();
                objs.add(o);
            }
        }
        return objs;
    }

    public synchronized void reset() { objs = null; }

    private static Map<String, Double> extras(double spend, int seed) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("conversations", Math.floor(spend / (70000 + (seed % 3) * 20000)));
        m.put("checkouts", Math.floor(spend / (130000 + (seed % 4) * 30000)));
        m.put("leads", Math.floor(spend / (90000 + (seed % 3) * 25000)));
        m.put("leadsOnMeta", Math.floor(spend / (110000 + (seed % 3) * 25000)));
        m.put("comments", Math.floor(spend / (40000 + (seed % 4) * 15000)));
        return m;
    }

    private static Metrics metrics(double spend, double results, int seed) {
        Map<String, Double> x = extras(spend, seed);
        double impressions = spend * 9;
        return new Metrics(spend, impressions, Math.round(impressions / (1.3 + (seed % 4) * 0.9)), Math.round(spend / 900), results,
                results > 0 ? spend / results : null, results * 380000, spend > 0 ? (results * 380000) / spend : null,
                x.get("conversations"), x.get("checkouts"), x.get("leads"), x.get("leadsOnMeta"), x.get("comments"));
    }

    /** Danh sách camp với số liệu "hôm nay" theo giờ hiện tại */
    public synchronized List<AdObject> list() {
        LocalTime t = LocalTime.now();
        double h = t.getHour() + t.getMinute() / 60.0;
        List<AdObject> out = new ArrayList<>();
        for (AdObject o : objs()) {
            boolean active = o.isActive();
            double spend = active ? Math.round(o.dailyBudget * Math.min(1, h / 24) * (0.7 + (o.seed % 4) * 0.12)) : 0;
            double results = active ? Math.floor(spend / (60000 + o.seed * 25000 + (o.seed % 2) * 90000)) : 0;
            AdObject c = o.copy();
            c.metrics = metrics(spend, results, o.seed);
            out.add(c);
        }
        return out;
    }

    /** Số liệu giả cho khoảng days ngày (không phụ thuộc trạng thái hiện tại) */
    public synchronized Map<String, Metrics> range(int days) {
        days = Math.max(1, Math.min(days, 400));
        Map<String, Metrics> out = new LinkedHashMap<>();
        for (AdObject o : objs()) {
            double spend = Math.round(o.dailyBudget * days * (0.75 + (o.seed % 4) * 0.1));
            double results = Math.floor(spend / (60000 + o.seed * 25000 + (o.seed % 2) * 90000));
            out.put(o.id, metrics(spend, results, o.seed));
        }
        return out;
    }

    public synchronized void setStatus(String id, boolean on) {
        for (AdObject o : objs()) if (o.id.equals(id)) o.status = o.effective = on ? "ACTIVE" : "PAUSED";
    }

    public synchronized void setBudget(String id, double amount) {
        for (AdObject o : objs()) if (o.id.equals(id)) o.dailyBudget = amount;
    }
}
