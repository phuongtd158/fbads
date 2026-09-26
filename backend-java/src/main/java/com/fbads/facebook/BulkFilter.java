package com.fbads.facebook;

import com.fbads.common.Fmt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Chọn camp / nhóm QC theo ĐIỀU KIỆN (lịch "Theo điều kiện"), bản Java của matchFilter/describeFilter trong shared/bulk.mjs.
 * f = { level, op, x, y, name, status, onlyRunning, account }
 */
public final class BulkFilter {
    private BulkFilter() {}

    private static String str(Map<String, Object> f, String k) { Object v = f.get(k); return v == null ? "" : String.valueOf(v); }

    private static double num(Map<String, Object> f, String k) { Object v = f.get(k); return v instanceof Number n ? n.doubleValue() : Double.NaN; }

    public static String statusOf(Map<String, Object> f) {
        String s = str(f, "status");
        if (s.equals("all") || s.equals("running") || s.equals("off")) return s;
        return Boolean.TRUE.equals(f.get("onlyRunning")) ? "running" : "all";
    }

    public static List<String> nameTerms(String name) {
        return Arrays.stream(name.split(",")).map(t -> t.trim().toLowerCase()).filter(t -> !t.isEmpty()).toList();
    }

    private static boolean test(String op, double b, double x, double y) {
        return switch (op) {
            case "lt" -> b < x;
            case "lte" -> b <= x;
            case "gt" -> b > x;
            case "gte" -> b >= x;
            case "between" -> b >= Math.min(x, y) && b <= Math.max(x, y);
            default -> true;
        };
    }

    /** Các mục khớp điều kiện. Bỏ qua mục đã lưu trữ/xoá. "Đang chạy" tính như cột Phân phối. */
    public static List<AdObject> match(List<AdObject> objs, Map<String, Object> f) {
        String level = "adset".equals(str(f, "level")) ? "adset" : "campaign";
        List<String> terms = nameTerms(str(f, "name"));
        String op = List.of("lt", "lte", "gt", "gte", "between").contains(str(f, "op")) ? str(f, "op") : "any";
        String st = statusOf(f);
        String account = str(f, "account");
        Delivery.View dv = st.equals("running") ? new Delivery.View(objs) : null;
        List<AdObject> out = new ArrayList<>();
        for (AdObject o : objs) {
            if (!level.equals(o.level)) continue;
            if ("ARCHIVED".equals(o.effective) || "DELETED".equals(o.effective)) continue;
            if (!account.isEmpty() && !account.equals(o.accountId)) continue;
            if (dv != null && !dv.running(o)) continue;
            if (st.equals("off") && !"PAUSED".equals(o.status)) continue;
            if (!terms.isEmpty() && terms.stream().noneMatch(t -> String.valueOf(o.name).toLowerCase().contains(t))) continue;
            if (!op.equals("any") && (o.dailyBudget == null || !test(op, o.dailyBudget, num(f, "x"), num(f, "y")))) continue;
            out.add(o);
        }
        return out;
    }

    /** Mô tả điều kiện cho người đọc, vd "Nhóm QC ngân sách dưới 100.000 · tên chứa “Phương” · đang chạy" */
    public static String describe(Map<String, Object> f) {
        List<String> parts = new ArrayList<>();
        boolean adset = "adset".equals(str(f, "level"));
        String head = adset ? "Nhóm QC" : "Chiến dịch";
        String op = str(f, "op");
        double x = num(f, "x"), y = num(f, "y");
        switch (op) {
            case "between" -> head += " ngân sách từ " + Fmt.money(Math.min(x, y)) + " đến " + Fmt.money(Math.max(x, y));
            case "lt" -> head += " ngân sách dưới " + Fmt.money(x);
            case "gt" -> head += " ngân sách trên " + Fmt.money(x);
            case "lte" -> head += " ngân sách từ " + Fmt.money(x) + " trở xuống";
            case "gte" -> head += " ngân sách từ " + Fmt.money(x) + " trở lên";
            default -> { }
        }
        parts.add(head);
        List<String> terms = Arrays.stream(str(f, "name").split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        if (!terms.isEmpty()) parts.add("tên chứa " + String.join(" hoặc ", terms.stream().map(t -> "“" + t + "”").toList()));
        String st = statusOf(f);
        if (st.equals("running")) parts.add("đang chạy");
        else if (st.equals("off")) parts.add("đang tắt");
        if (!str(f, "account").isEmpty()) parts.add("tài khoản " + str(f, "account"));
        if (parts.size() == 1 && (op.isEmpty() || op.equals("any"))) parts.set(0, adset ? "Mọi nhóm QC" : "Mọi chiến dịch");
        return String.join(" · ", parts);
    }
}
