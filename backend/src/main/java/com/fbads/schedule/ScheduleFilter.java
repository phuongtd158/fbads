package com.fbads.schedule;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.Fmt;
import com.fbads.common.JsonConverters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Điều kiện của lịch "Theo điều kiện" (lọc lại mỗi lần chạy): cấp (campaign | adset), ngân sách so với x (op = lt, lte,
 * gt, gte, between với y, any = không xét), cụm tên (cách nhau dấu phẩy), trạng thái (all | running | off), tài khoản.
 * onlyRunning: cách ghi cũ của status = running. Lọc thật nằm ở engine/BulkFilter.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduleFilter(String level, String op, Double x, Double y, String name, String status, Boolean onlyRunning,
        String account) {
    public static final List<String> OPS = List.of("lt", "lte", "gt", "gte", "between");
    public static final List<String> STATUSES = List.of("all", "running", "off");

    public boolean forAdsets() { return "adset".equals(level); }

    /** Phép so ngân sách; trống hoặc lạ = "any" (không xét ngân sách) */
    public String budgetOp() { return op != null && OPS.contains(op) ? op : "any"; }

    /** Trạng thái cần lọc: all | running | off (dữ liệu cũ chỉ có onlyRunning) */
    public String statusMode() {
        if (status != null && STATUSES.contains(status)) return status;
        return Boolean.TRUE.equals(onlyRunning) ? "running" : "all";
    }

    /** Các cụm tên cần chứa (viết thường, bỏ khoảng trắng thừa) */
    public List<String> nameTerms() {
        return terms().stream().map(String::toLowerCase).toList();
    }

    private List<String> terms() {
        return name == null ? List.of() : Arrays.stream(name.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    public String accountOrEmpty() { return account == null ? "" : account; }

    /** Mô tả cho người đọc, vd "Nhóm QC ngân sách dưới 100.000 · tên chứa “Phương” · đang chạy" */
    public String describe() {
        List<String> parts = new ArrayList<>();
        String head = forAdsets() ? "Nhóm QC" : "Chiến dịch";
        double vx = x == null ? Double.NaN : x, vy = y == null ? Double.NaN : y;
        switch (op == null ? "" : op) {
            case "between" -> head += " ngân sách từ " + Fmt.money(Math.min(vx, vy)) + " đến " + Fmt.money(Math.max(vx, vy));
            case "lt" -> head += " ngân sách dưới " + Fmt.money(vx);
            case "gt" -> head += " ngân sách trên " + Fmt.money(vx);
            case "lte" -> head += " ngân sách từ " + Fmt.money(vx) + " trở xuống";
            case "gte" -> head += " ngân sách từ " + Fmt.money(vx) + " trở lên";
            default -> { }
        }
        parts.add(head);
        if (!terms().isEmpty()) parts.add("tên chứa " + String.join(" hoặc ", terms().stream().map(t -> "“" + t + "”").toList()));
        String st = statusMode();
        if (st.equals("running")) parts.add("đang chạy");
        else if (st.equals("off")) parts.add("đang tắt");
        if (!accountOrEmpty().isEmpty()) parts.add("tài khoản " + account);
        if (parts.size() == 1 && (op == null || op.isEmpty() || op.equals("any")))
            parts.set(0, forAdsets() ? "Mọi nhóm QC" : "Mọi chiến dịch");
        return String.join(" · ", parts);
    }

    public static class Converter extends JsonConverters.Of<ScheduleFilter> {
        public Converter() { super(ScheduleFilter.class); }
    }
}
