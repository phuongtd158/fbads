package com.fbads.facebook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cột "Phân phối" giống Ads Manager (bản Java của shared/delivery.mjs): không chỉ bật/tắt của chính mục đó mà còn xét
 * các nhóm QC bên trong (camp bật nhưng mọi nhóm QC tắt → "Nhóm quảng cáo đang tắt", KHÔNG tính là đang chạy).
 */
public final class Delivery {
    public record Info(String label, boolean running) {}

    public static final Map<String, Info> INFO = Map.ofEntries(
            Map.entry("active", new Info("Đang hoạt động", true)),
            Map.entry("learning", new Info("Đang học", true)),
            Map.entry("review", new Info("Đang xét duyệt", false)),
            Map.entry("issues", new Info("Có vấn đề", false)),
            Map.entry("scheduled", new Info("Đã lên lịch", false)),
            Map.entry("adsetsOff", new Info("Nhóm quảng cáo đang tắt", false)),
            Map.entry("campaignOff", new Info("Chiến dịch đang tắt", false)),
            Map.entry("rejected", new Info("Bị từ chối", false)),
            Map.entry("completed", new Info("Hoàn tất", false)),
            Map.entry("off", new Info("Tắt", false)),
            Map.entry("archived", new Info("Lưu trữ", false)),
            Map.entry("deleted", new Info("Đã xoá", false)));

    private Delivery() {}

    private static String own(AdObject o) {
        String e = o.effective == null ? "" : o.effective;
        if (e.equals("DELETED")) return "deleted";
        if (e.equals("ARCHIVED")) return "archived";
        if ("PAUSED".equals(o.status)) return "off";
        if (e.equals("CAMPAIGN_PAUSED")) return "campaignOff";
        if (e.equals("ADSET_PAUSED")) return "adsetsOff";
        if (e.equals("DISAPPROVED")) return "rejected";
        if (e.equals("PENDING_REVIEW") || e.equals("IN_PROCESS")) return "review";
        if (e.equals("WITH_ISSUES")) return "issues";
        return "";
    }

    private static String adset(AdObject a, long now) {
        String k = own(a);
        if (!k.isEmpty()) return k;
        if (a.endTime != null && a.endTime != 0 && a.endTime < now) return "completed";
        if (a.startTime != null && a.startTime != 0 && a.startTime > now) return "scheduled";
        if (a.learning) return "learning";
        return a.isActive() ? "active" : "off";
    }

    private static String campaign(AdObject c, List<AdObject> adsets, long now) {
        String k = own(c);
        if (!k.isEmpty()) return k;
        if (adsets.isEmpty()) return c.isActive() ? "active" : "off"; // chưa có dữ liệu nhóm QC (vd dữ liệu giả)
        List<String> ks = adsets.stream().map(a -> adset(a, now)).toList();
        if (ks.contains("active") || ks.contains("learning")) return "active";
        for (String x : List.of("review", "issues", "scheduled")) if (ks.contains(x)) return x;
        java.util.function.Predicate<String> gone = x -> x.equals("completed") || x.equals("archived") || x.equals("deleted");
        if (ks.contains("completed") && ks.stream().allMatch(gone)) return "completed";
        if (ks.contains("rejected") && ks.stream().allMatch(x -> x.equals("rejected") || gone.test(x))) return "rejected";
        return "adsetsOff";
    }

    /** id → khoá trạng thái phân phối cho toàn bộ danh sách */
    public static Map<String, String> map(List<AdObject> objs, long now) {
        Map<String, List<AdObject>> byCamp = new HashMap<>();
        for (AdObject o : objs) if ("adset".equals(o.level)) byCamp.computeIfAbsent(o.campaignId, k -> new ArrayList<>()).add(o);
        Map<String, String> out = new HashMap<>();
        for (AdObject o : objs) out.put(o.id, "adset".equals(o.level) ? adset(o, now) : campaign(o, byCamp.getOrDefault(o.id, List.of()), now));
        return out;
    }

    /** Hỏi nhanh "mục này có đang thật sự chạy không" + nhãn trạng thái (dùng cho rule) */
    public static final class View {
        private final Map<String, String> map;

        public View(List<AdObject> objs) { this.map = Delivery.map(objs, System.currentTimeMillis()); }

        public boolean running(AdObject o) { Info i = INFO.get(map.get(o.id)); return i != null && i.running(); }

        public String label(AdObject o) { Info i = INFO.get(map.get(o.id)); return i == null ? "" : i.label(); }
    }
}
