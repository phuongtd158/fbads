package com.fbads.company;

import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;
import com.fbads.company.CompanyJson.NewReport;
import com.fbads.company.CompanyJson.ReportUpdate;
import com.fbads.dto.CompanyConfigPatch;
import com.fbads.dto.CompanyReportPatch;
import com.fbads.entity.CompanyConfig;
import com.fbads.entity.CompanyReport;
import com.fbads.validation.Result;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Luật báo cáo công ty (bản Java của shared/companyReport.mjs, giao diện vẫn dùng bản JS).
 * Mỗi báo cáo = 1 Team công ty × 1 ngày × 1 mốc. Mốc 9h chốt số cả ngày hôm qua; 12h, 17h, 22h là luỹ kế hôm nay.
 * Cả 7 số đều lấy từ Facebook: Đơn hàng = Kết quả, DSO sau VAT = Doanh thu (theo "Loại kết quả" ở Cài đặt → Chung).
 */
public final class CompanyRules {
    private CompanyRules() {}

    public static final List<Integer> SLOTS = List.of(9, 12, 17, 22);
    public static final List<String> MODES = List.of("preview", "approve", "auto");
    public static final int MAX_TEAMS = 30;
    public static final int MAX_TEXT = 2000;
    public static final double MAX_NUMBER = 1e14;
    public static final int MAX_LEAD_MIN = 60;
    public static final int MAX_REASON = 500;
    public static final int DATE_RULE = 3;

    /** 7 số của form, đúng tên trường API công ty */
    public record MetricDef(String key, String label, boolean money) {}

    public static final List<MetricDef> METRICS = List.of(
            new MetricDef("spend", "Chi tiêu Ads", true),
            new MetricDef("messages", "Tin nhắn", false),
            new MetricDef("phones", "Số điện thoại", false),
            new MetricDef("orders", "Số đơn hàng", false),
            new MetricDef("dso_after", "DSO sau VAT", true),
            new MetricDef("impressions", "Lượt hiển thị", false),
            new MetricDef("clicks", "Lượt nhấp", false));
    public static final List<String> METRIC_KEYS = METRICS.stream().map(MetricDef::key).toList();
    public static final List<String> TEXT_KEYS = List.of("notes", "issue", "resolution");

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern TEAM_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9_]{1,40}$");

    /** Phút trong ngày tool làm báo cáo của mốc: đúng giờ mốc trừ đi số phút làm sớm */
    public static int fireMinute(int slot, int leadMin) { return slot * 60 - Math.max(0, Math.min(MAX_LEAD_MIN, leadMin)); }

    /** Ngày của báo cáo = ngày của số liệu: mốc 9h là hôm qua, mốc khác là hôm nay */
    public static String reportDate(int slot, String today) { return slot == 9 ? LocalDate.parse(today).minusDays(1).toString() : today; }

    /** Ngày tool chạy / gửi báo cáo của mốc: mốc 9h chạy sáng hôm sau ngày báo cáo */
    public static String submitDate(int slot, String date) { return slot == 9 ? LocalDate.parse(date).plusDays(1).toString() : date; }

    /** Mốc 9h: bản ghi trên công ty đã có (chưa khoá) thì tool cập nhật vào đó với lý do này */
    public static String closeReason(String date) { return "Chốt số liệu cả ngày " + dm(date); }

    public static boolean updatesExisting(int slot) { return slot == 9; }

    /** Khoảng số liệu Facebook của mốc */
    public static String rangeOf(int slot) { return slot == 9 ? "yesterday" : "today"; }

    public static String dm(String iso) { return iso.substring(8, 10) + "/" + iso.substring(5, 7); }

    /** Từ khoá tên chiến dịch: "CT01, hoạt huyết" → [ct01, hoạt huyết] */
    public static List<String> matchWords(String s) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String w : (s == null ? "" : s).split("[,;\\n]+")) { String t = w.trim().toLowerCase(); if (!t.isEmpty()) out.add(t); }
        return new ArrayList<>(out);
    }

    /**
     * Chiến dịch thuộc một Team: đúng tài khoản QC (nếu có chọn) VÀ tên chứa một trong các từ khoá (nếu có nhập). Team
     * chưa chọn gì → không có.
     */
    public static List<AdObject> teamCampaigns(CompanyConfig.Team team, List<AdObject> objs) {
        List<String> accs = team == null || team.accountIds() == null ? List.of() : team.accountIds();
        List<String> words = matchWords(team == null ? null : team.match());
        if (accs.isEmpty() && words.isEmpty()) return List.of();
        return objs.stream().filter(o -> o.isCampaign()
                && (accs.isEmpty() || accs.contains(o.accountId() == null ? "" : o.accountId()))
                && (words.isEmpty() || words.stream().anyMatch(w -> (o.name() == null ? "" : o.name()).toLowerCase().contains(w)))).toList();
    }

    /**
     * Cộng số Facebook của các chiến dịch → 7 số của form (Tin nhắn = cuộc trò chuyện, SĐT = khách hàng tiềm năng, Đơn
     * = kết quả, DSO = doanh thu)
     */
    public static Map<String, Long> sumMetrics(List<AdObject> camps, Map<String, Metrics> data) {
        double[] t = new double[7];
        for (AdObject o : camps) {
            Metrics m = data == null ? null : data.get(o.id());
            if (m == null) continue;
            t[0] += m.spend(); t[1] += m.conversations(); t[2] += m.leads(); t[3] += m.results(); t[4] += m.revenue();
            t[5] += m.impressions(); t[6] += m.clicks();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++)
            out.put(List.of("spend", "messages", "phones", "orders", "dso_after", "impressions", "clicks").get(i), Math.round(t[i]));
        return out;
    }

    // ------------------------------------------------------------------ Kiểm tra dữ liệu

    /** Cài đặt đã qua kiểm tra: chỉ các khoá được gửi (mật khẩu trống = giữ mật khẩu cũ) */
    public record ConfigValue(Boolean enabled, String mode, List<Integer> slots, Integer leadMin, String baseUrl,
            String email, String password, List<CompanyConfig.Team> teams) {}

    /** JS Number(x) cho giá trị JSON đã đọc thành Object */
    static double jsNumber(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return 0;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static boolean isInt(double d) { return Double.isFinite(d) && d == Math.rint(d); }

    public static Result<ConfigValue> validateConfig(CompanyConfigPatch p, CompanyConfig current) {
        Map<String, String> e = new LinkedHashMap<>();
        Boolean enabled = null;
        String mode = null, baseUrl = null, email = null, password = null;
        List<Integer> slots = null;
        Integer leadMin = null;
        List<CompanyConfig.Team> teams = null;
        if (p.has("enabled")) enabled = Boolean.TRUE.equals(p.getEnabled());
        if (p.has("mode")) { if (MODES.contains(p.getMode())) mode = p.getMode(); else e.put("mode", "Chế độ gửi không hợp lệ"); }
        if (p.has("slots")) {
            LinkedHashSet<Double> s = new LinkedHashSet<>();
            if (p.getSlots() != null) for (Object x : p.getSlots()) s.add(jsNumber(x));
            if (s.stream().anyMatch(x -> !SLOTS.contains((int) (double) x) || x != Math.rint(x)))
                e.put("slots", "Mốc báo cáo không hợp lệ");
            else slots = SLOTS.stream().filter(x -> s.contains((double) x)).toList();
        }
        if (p.has("leadMin")) {
            double n = p.getLeadMin() == null ? 0 : p.getLeadMin();
            if (!isInt(n) || n < 0 || n > MAX_LEAD_MIN) e.put("leadMin", "Số phút làm sớm phải từ 0 đến " + MAX_LEAD_MIN);
            else leadMin = (int) n;
        }
        if (p.has("baseUrl")) {
            String u = (p.getBaseUrl() == null ? "" : p.getBaseUrl()).trim().replaceAll("/+$", "");
            if (u.isEmpty()) u = CompanyConfig.DEFAULT_BASE_URL;
            boolean ok = false;
            try {
                URI x = new URI(u);
                ok = "https".equals(x.getScheme()) && x.getHost() != null && x.getRawUserInfo() == null
                        && (x.getRawPath() == null || x.getRawPath().isEmpty() || x.getRawPath().equals("/"))
                        && x.getRawQuery() == null && x.getRawFragment() == null;
            } catch (Exception ignored) { /* sai dạng */ }
            if (ok) baseUrl = u; else e.put("baseUrl", "Địa chỉ hệ thống phải là https://tên-miền (không kèm đường dẫn)");
        }
        if (p.has("email")) {
            String m = (p.getEmail() == null ? "" : p.getEmail()).trim();
            if (!m.isEmpty() && !EMAIL.matcher(m).matches()) e.put("email", "Email không hợp lệ"); else email = m;
        }
        if (p.has("password")) {
            String pw = p.getPassword() == null ? "" : p.getPassword();
            if (pw.length() > 200) e.put("password", "Mật khẩu quá dài"); else if (!pw.isEmpty()) password = pw;
        }
        if (p.has("teams")) {
            List<CompanyConfigPatch.TeamRequest> raw = p.getTeams();
            if (raw == null) e.put("teams", "Danh sách Team không hợp lệ");
            else if (raw.size() > MAX_TEAMS) e.put("teams", "Tối đa " + MAX_TEAMS + " Team");
            else {
                List<CompanyConfig.Team> out = new ArrayList<>();
                for (int i = 0; i < raw.size(); i++) {
                    CompanyConfigPatch.TeamRequest t = raw.get(i) == null
                            ? new CompanyConfigPatch.TeamRequest(null, null, null, null, null) : raw.get(i);
                    String id = (t.id() == null ? "" : t.id()).trim();
                    String lb = t.code() != null && !t.code().isEmpty() ? t.code()
                            : t.name() != null && !t.name().isEmpty() ? t.name()
                            : "Team " + (i + 1);
                    String label = lb.length() > 40 ? lb.substring(0, 40) : lb;
                    if (!TEAM_ID.matcher(id).matches()) { e.put("teams", label + ": hãy chọn Team của hệ thống công ty"); break; }
                    if (out.stream().anyMatch(x -> x.id().equals(id))) { e.put("teams", label + " bị chọn hai lần"); break; }
                    LinkedHashSet<String> accs = new LinkedHashSet<>();
                    if (t.accountIds() != null) for (String a : t.accountIds()) {
                        String v = (a == null ? "" : a).trim().replaceFirst("(?i)^act_", "");
                        if (!v.isEmpty()) accs.add(v);
                    }
                    if (accs.stream().anyMatch(a -> !ACCOUNT_ID.matcher(a).matches())) {
                        e.put("teams", label + ": tài khoản quảng cáo không hợp lệ");
                        break;
                    }
                    String match = String.join(", ", matchWords(t.match()));
                    if (match.length() > 300) { e.put("teams", label + ": từ khoá tên chiến dịch quá dài"); break; }
                    if (accs.isEmpty() && match.isEmpty()) {
                        e.put("teams", label + ": hãy chọn tài khoản quảng cáo hoặc nhập từ khoá tên chiến dịch");
                        break;
                    }
                    String code = (t.code() == null ? "" : t.code()).trim(), name = (t.name() == null ? "" : t.name()).trim();
                    out.add(new CompanyConfig.Team(id, code.length() > 40 ? code.substring(0, 40) : code,
                            name.length() > 120 ? name.substring(0, 120) : name,
                            new ArrayList<>(accs), match));
                }
                if (!e.containsKey("teams")) teams = out;
            }
        }
        boolean effEnabled = enabled != null ? enabled : current.isEnabled();
        String effEmail = email != null ? email : current.getEmail();
        List<CompanyConfig.Team> effTeams = teams != null ? teams : current.getTeams();
        List<Integer> effSlots = slots != null ? slots : current.getSlots();
        if (effEnabled && !e.containsKey("email") && !e.containsKey("password")) {
            if (effEmail.isEmpty()) e.put("email", "Cần email đăng nhập hệ thống công ty");
            else if (password == null && current.getPassword().isEmpty()) e.put("password", "Cần mật khẩu đăng nhập hệ thống công ty");
        }
        if (effEnabled && !e.containsKey("teams") && effTeams.isEmpty()) e.put("teams", "Thêm ít nhất một Team để bật báo cáo tự động");
        if (effEnabled && !e.containsKey("slots") && effSlots.isEmpty()) e.put("slots", "Chọn ít nhất một mốc báo cáo");
        return new Result<>(e, List.of(), new ConfigValue(enabled, mode, slots, leadMin, baseUrl, email, password, teams));
    }

    /** Phần sửa trên một bản báo cáo: số (trống = chưa nhập) và 3 ô chữ. metrics/texts chỉ có các khoá được gửi. */
    public record PatchValue(Map<String, Long> metrics, Map<String, String> texts) {}

    public static Result<PatchValue> validateReportPatch(CompanyReportPatch p) {
        Map<String, String> e = new LinkedHashMap<>();
        Map<String, Long> metrics = new LinkedHashMap<>();
        Map<String, Object> src = p.getMetrics();
        if (src != null) for (MetricDef m : METRICS) {
            if (!src.containsKey(m.key())) continue;
            Object v = src.get(m.key());
            if (v == null || "".equals(v)) { metrics.put(m.key(), null); continue; }
            double n = jsNumber(v);
            if (!isInt(n) || n < 0 || n > MAX_NUMBER) { e.put(m.key(), m.label() + " phải là số nguyên không âm"); continue; }
            metrics.put(m.key(), (long) n);
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (String k : TEXT_KEYS) if (p.has(k)) {
            String s = p.text(k) == null ? "" : p.text(k);
            if (s.length() > MAX_TEXT) e.put(k, "Tối đa " + MAX_TEXT + " ký tự"); else texts.put(k, s);
        }
        return new Result<>(e, List.of(), new PatchValue(metrics, texts));
    }

    /** Các số còn thiếu (chưa nhập) → không được gửi. Gửi 0 cho Đơn/DSO làm hệ thống công ty tính sai CP/DS. */
    public static List<MetricDef> missingMetrics(CompanyReport r) {
        return METRICS.stream().filter(m -> r.getMetrics() == null || r.getMetrics().get(m.key()) == null).toList();
    }

    /** Lý do KHÔNG tự gửi (chế độ Tự động gửi): số trông bất thường. Rỗng = gửi được. */
    public static List<String> anomalies(CompanyReport r) {
        List<String> out = new ArrayList<>();
        List<MetricDef> miss = missingMetrics(r);
        if (!miss.isEmpty()) out.add("Còn thiếu " + String.join(", ", miss.stream().map(MetricDef::label).toList()));
        if (r.getCampaigns() == null || r.getCampaigns().isEmpty()) out.add("Team không khớp chiến dịch nào (kiểm tra cấu hình Team)");
        Long orders = r.getMetrics().get("orders"), dso = r.getMetrics().get("dso_after");
        if (orders != null && orders > 0 && !(dso != null && dso > 0))
            out.add("Có đơn nhưng doanh thu bằng 0 (tài khoản chưa báo giá trị đơn về Facebook)");
        return out;
    }

    /** Body của POST /api/reports */
    public static NewReport payloadOf(CompanyReport r) {
        return new NewReport(r.getTeamId(), r.getDate(), r.getSlot(), metricsOf(r), r.getNotes(), r.getIssue(), r.getResolution());
    }

    /** Body cập nhật báo cáo đã có trên công ty: như gửi mới, kèm lần sửa hiện tại và lý do */
    public static ReportUpdate updatePayloadOf(CompanyReport r, Integer revision, String reason) {
        return new ReportUpdate(r.getTeamId(), r.getDate(), r.getSlot(), metricsOf(r), r.getNotes(), r.getIssue(),
                r.getResolution(), revision, reason == null ? "" : reason.trim());
    }

    /** Số liệu gửi đi, đủ mọi khoá theo thứ tự METRIC_KEYS (chưa nhập = null) */
    private static Map<String, Long> metricsOf(CompanyReport r) {
        Map<String, Long> metrics = new LinkedHashMap<>();
        for (String k : METRIC_KEYS) metrics.put(k, r.getMetrics().get(k));
        return metrics;
    }

    public static String validateReason(String reason) {
        String s = reason == null ? "" : reason.trim();
        if (s.isEmpty()) return "Nhập lý do cập nhật (công ty bắt buộc)";
        if (s.length() > MAX_REASON) return "Lý do tối đa " + MAX_REASON + " ký tự";
        return "";
    }
}
