package com.fbads.engine;

import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;
import com.fbads.entity.ScheduleFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Chọn camp / nhóm QC theo điều kiện của lịch "Theo điều kiện" (bản Java của matchFilter trong shared/bulk.mjs).
 * Điều kiện và câu mô tả: entity/ScheduleFilter.
 */
public final class BulkFilter {
    private BulkFilter() {}

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
    public static List<AdObject> match(List<AdObject> objs, ScheduleFilter f) {
        AdLevel level = f.forAdsets() ? AdLevel.ADSET : AdLevel.CAMPAIGN;
        List<String> terms = f.nameTerms();
        String op = f.budgetOp();
        String st = f.statusMode();
        String account = f.accountOrEmpty();
        double x = f.x() == null ? Double.NaN : f.x(), y = f.y() == null ? Double.NaN : f.y();
        Delivery.View dv = st.equals("running") ? new Delivery.View(objs) : null;
        List<AdObject> out = new ArrayList<>();
        for (AdObject o : objs) {
            if (o.level() != level) continue;
            if (o.isRemoved()) continue;
            if (!account.isEmpty() && !account.equals(o.accountId())) continue;
            if (dv != null && !dv.running(o)) continue;
            if (st.equals("off") && !"PAUSED".equals(o.status())) continue;
            if (!terms.isEmpty() && terms.stream().noneMatch(t -> String.valueOf(o.name()).toLowerCase().contains(t))) continue;
            if (!op.equals("any") && (o.dailyBudget() == null || !test(op, o.dailyBudget(), x, y))) continue;
            out.add(o);
        }
        return out;
    }
}
