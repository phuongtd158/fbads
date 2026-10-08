package com.fbads.facebook;

import com.fbads.ads.AdLevel;
import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

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
                String st = i % 3 == 2 ? "PAUSED" : "ACTIVE";
                Account a = i < 4 ? ACCOUNTS.get(0) : ACCOUNTS.get(1);
                objs.add(AdObject.builder("mock_" + (i + 1), NAMES[i], AdLevel.CAMPAIGN).state(st, st).budget(BUDGETS[i])
                        .seed(i + 1).learning(i == 4).account(a.accountId(), a.accountName(), a.currency()).build());
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
            double spend = active ? Math.round(o.dailyBudget() * Math.min(1, h / 24) * (0.7 + (o.seed() % 4) * 0.12)) : 0;
            double results = active ? Math.floor(spend / (60000 + o.seed() * 25000 + (o.seed() % 2) * 90000)) : 0;
            out.add(o.withMetrics(metrics(spend, results, o.seed())));
        }
        return out;
    }

    /** Số liệu giả cho khoảng days ngày (không phụ thuộc trạng thái hiện tại) */
    public synchronized Map<String, Metrics> range(int days) {
        days = Math.max(1, Math.min(days, 400));
        Map<String, Metrics> out = new LinkedHashMap<>();
        for (AdObject o : objs()) {
            double spend = Math.round(o.dailyBudget() * days * (0.75 + (o.seed() % 4) * 0.1));
            double results = Math.floor(spend / (60000 + o.seed() * 25000 + (o.seed() % 2) * 90000));
            out.put(o.id(), metrics(spend, results, o.seed()));
        }
        return out;
    }

    /**
     * Số liệu giả từng ngày của một camp/nhóm QC: ổn định theo camp và ngày (mở lại thấy cùng số), chi tiêu dao động
     * quanh ngân sách. null = không có mục này
     */
    public synchronized List<Map.Entry<String, Metrics>> trend(String id, List<String> dates) {
        AdObject o = objs().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (o == null) return null;
        List<Map.Entry<String, Metrics>> out = new ArrayList<>();
        for (String date : dates) {
            double n = LocalDate.parse(date).toEpochDay();
            DoubleUnaryOperator wave = k -> (Math.sin(n * k + o.seed() * 1.7) + 1) / 2; // 0..1
            double spend = Math.round(o.dailyBudget() * (0.55 + 0.45 * wave.applyAsDouble(0.9)));
            double results = Math.floor(spend / ((60000 + o.seed() * 25000 + (o.seed() % 2) * 90000) * (0.75
                    + 0.5 * wave.applyAsDouble(0.37))));
            out.add(Map.entry(date, metrics(spend, results, o.seed())));
        }
        return out;
    }

    public synchronized void setStatus(String id, boolean on) {
        for (AdObject o : objs()) if (o.id().equals(id)) o.applyStatus(on);
    }

    public synchronized void setBudget(String id, double amount) {
        for (AdObject o : objs()) if (o.id().equals(id)) o.applyBudget(amount);
    }
}
