package com.fbads.validation;

import com.fbads.common.Json;
import com.fbads.dto.SettingsPatch;
import com.fbads.entity.AppSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Kiểm tra phần cài đặt người dùng gửi lên (bản Java của validateSettings trong shared/validate.mjs).
 * Chỉ các khoá có trong patch mới được xét và mới được ghi (value = map khoá → giá trị đã chuẩn hoá).
 */
public final class SettingsValidator {
    private SettingsValidator() {}

    public static Result<Map<String, Object>> validate(SettingsPatch patch, AppSettings current) {
        Result.Collector c = new Result.Collector();
        Map<String, String> e = c.e;
        Map<String, Object> v = new LinkedHashMap<>();
        Predicate<String> has = patch::has;

        if (has.test("timezone")) {
            String t = Json.str(patch.timezone()).trim();
            if (!Checks.isTimezone(t)) e.put("timezone", "Múi giờ không hợp lệ (ví dụ Asia/Ho_Chi_Minh)"); else v.put("timezone", t);
        }
        if (has.test("ruleIntervalMin")) {
            double n = Json.num(patch.ruleIntervalMin());
            if (n != Math.rint(n) || n < Checks.INTERVAL_MIN || n > Checks.INTERVAL_MAX)
                e.put("ruleIntervalMin", "Chu kỳ kiểm tra rule từ " + Checks.INTERVAL_MIN + " đến " + Checks.INTERVAL_MAX + " phút");
            else v.put("ruleIntervalMin", (int) n);
        }
        if (has.test("reportTime")) {
            String t = Json.str(patch.reportTime());
            if (!t.isEmpty() && !Checks.isTime(t)) e.put("reportTime", "Giờ báo cáo không hợp lệ (HH:MM)"); else v.put("reportTime", t);
        }
        // Tài khoản quảng cáo: quản lý được nhiều tài khoản. adAccountId = tài khoản đầu tiên, giữ cho phần cũ.
        if (has.test("adAccountIds") || has.test("adAccountId")) {
            List<String> raw = has.test("adAccountIds") ? Json.strings(patch.adAccountIds()) : List.of(Json.str(patch.adAccountId()));
            List<String> ids = new ArrayList<>(new LinkedHashSet<>(
                    raw.stream().map(Checks::cleanAccountId).filter(s -> !s.isEmpty()).toList()));
            String bad = ids.stream().filter(a -> !Checks.checkAccountId(a).isEmpty()).findFirst().orElse(null);
            if (bad != null) e.put("adAccountId", Checks.checkAccountId(bad) + " — \"" + bad + "\"");
            else if (ids.size() > Checks.ACCOUNTS_MAX) e.put("adAccountId", "Tối đa " + Checks.ACCOUNTS_MAX + " tài khoản quảng cáo");
            else { v.put("adAccountIds", ids); v.put("adAccountId", ids.isEmpty() ? "" : ids.getFirst()); }
        }
        if (has.test("accessToken")) {
            String t = Json.str(patch.accessToken()).trim();
            if (!t.isEmpty()) {
                String m = Checks.checkToken(t);
                if (!m.isEmpty()) e.put("accessToken", m); else v.put("accessToken", t);
            }
        }
        if (has.test("resultAction")) {
            String r = Json.str(patch.resultAction()).trim();
            if (!r.matches("^[A-Za-z0-9_.]{2,100}$")) e.put("resultAction", "Loại kết quả không hợp lệ"); else v.put("resultAction", r);
        }
        if (has.test("apiVersion")) {
            String r = Json.str(patch.apiVersion()).trim();
            if (!r.matches("^v\\d+\\.\\d+$")) e.put("apiVersion", "Phiên bản API không hợp lệ (ví dụ v21.0)"); else v.put("apiVersion", r);
        }
        if (has.test("skipLearning")) v.put("skipLearning", Boolean.TRUE.equals(patch.skipLearning()));
        if (has.test("dailyChangeCapPct")) {
            double n = Json.num(patch.dailyChangeCapPct());
            if (n != Math.rint(n) || n < Checks.CAP_PCT_MIN || n > Checks.CAP_PCT_MAX)
                e.put("dailyChangeCapPct", "Giới hạn thay đổi ngân sách mỗi ngày từ " + Checks.CAP_PCT_MIN + "% đến "
                        + Checks.CAP_PCT_MAX + "%");
            else v.put("dailyChangeCapPct", (int) n);
        }
        if (has.test("killSwitchEnabled")) v.put("killSwitchEnabled", Boolean.TRUE.equals(patch.killSwitchEnabled()));
        // Cảnh báo bất thường (engine/AlertWatch), báo cáo tuần
        if (has.test("alertAccount")) v.put("alertAccount", Boolean.TRUE.equals(patch.alertAccount()));
        if (has.test("alertDisapproved")) v.put("alertDisapproved", Boolean.TRUE.equals(patch.alertDisapproved()));
        if (has.test("alertSpike")) v.put("alertSpike", Boolean.TRUE.equals(patch.alertSpike()));
        if (has.test("weeklyReport")) v.put("weeklyReport", Boolean.TRUE.equals(patch.weeklyReport()));
        if (has.test("spikePct")) {
            double n = Json.num(patch.spikePct());
            if (n != Math.rint(n) || n < 10 || n > 1000) e.put("spikePct", "Mức tăng vọt từ 10% đến 1000%");
            else v.put("spikePct", (int) n);
        }
        if (has.test("spikeMinSpend")) {
            double n = patch.spikeMinSpend() == null ? 0 : patch.spikeMinSpend();
            if (!Double.isFinite(n) || n < 0 || n > Checks.BUDGET_MAX) e.put("spikeMinSpend", "Chi tiêu tối thiểu phải là số không âm");
            else v.put("spikeMinSpend", Math.round(n));
        }
        if (has.test("dailySpendLimit")) {
            double n = patch.dailySpendLimit() == null ? 0 : patch.dailySpendLimit();
            if (!Double.isFinite(n) || n < 0 || n > Checks.BUDGET_MAX)
                e.put("dailySpendLimit", "Mức chi tiêu tối đa mỗi ngày phải là số không âm");
            else v.put("dailySpendLimit", Math.round(n));
        }
        if (has.test("killScope")) {
            String k = Json.str(patch.killScope());
            if (k.equals("account") || k.equals("total")) v.put("killScope", k); else e.put("killScope", "Phạm vi dừng khẩn không hợp lệ");
        }
        // Mục tiêu theo từng tài khoản: { [id]: { cpa, roas, dailySpendLimit } }. 0/trống = chưa đặt.
        if (has.test("accountTargets")) {
            Map<String, SettingsPatch.AccountTarget> raw = patch.accountTargets();
            Map<String, Map<String, Number>> out = new LinkedHashMap<>();
            if (raw == null) e.put("accountTargets", "Mục tiêu theo tài khoản không hợp lệ");
            else {
                Map<String, Double> cap = new LinkedHashMap<>();
                cap.put("cpa", Checks.BUDGET_MAX); cap.put("roas", 100.0); cap.put("dailySpendLimit", Checks.BUDGET_MAX);
                Map<String, String> label = Map.of("cpa", "CPA mục tiêu", "roas", "ROAS mục tiêu", "dailySpendLimit", "Mức dừng khẩn");
                outer:
                for (var entry : raw.entrySet()) {
                    String id = entry.getKey();
                    SettingsPatch.AccountTarget t = entry.getValue();
                    String sid = id.length() > 20 ? id.substring(0, 20) : id;
                    if (!id.matches("^[A-Za-z0-9_]{1,40}$")) { e.put("accountTargets", "Mã tài khoản không hợp lệ: " + sid); break; }
                    Map<String, Number> o = new LinkedHashMap<>();
                    for (String k : cap.keySet()) {
                        Double x = t == null ? null : switch (k) {
                            case "cpa" -> t.cpa();
                            case "roas" -> t.roas();
                            default -> t.dailySpendLimit();
                        };
                        double n = x == null ? 0 : x;
                        if (!Double.isFinite(n) || n < 0 || n > cap.get(k)) {
                            e.put("accountTargets", label.get(k) + " của tài khoản " + sid + " phải là số không âm"
                                    + (k.equals("roas") ? " (tối đa 100)" : ""));
                            break outer;
                        }
                        if (n > 0) o.put(k, k.equals("roas") ? Math.round(n * 100) / 100.0 : Math.round(n));
                    }
                    if (!o.isEmpty()) out.put(id, o);
                    if (out.size() > Checks.ACCOUNTS_MAX) {
                        e.put("accountTargets", "Tối đa " + Checks.ACCOUNTS_MAX + " tài khoản");
                        break;
                    }
                }
            }
            if (!e.containsKey("accountTargets")) v.put("accountTargets", out);
        }
        if (has.test("mock")) v.put("mock", Boolean.TRUE.equals(patch.mock()));
        if (has.test("dryRun")) v.put("dryRun", Boolean.TRUE.equals(patch.dryRun()));

        // Giá trị sau khi áp dụng (để kiểm tra các điều kiện liên quan nhiều khoá)
        boolean killOn = v.containsKey("killSwitchEnabled") ? (boolean) v.get("killSwitchEnabled") : current.isKillSwitchEnabled();
        double limit = v.containsKey("dailySpendLimit") ? ((Number) v.get("dailySpendLimit")).doubleValue() : current.getDailySpendLimit();
        String scope = v.containsKey("killScope") ? (String) v.get("killScope") : current.getKillScope();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> targets = v.containsKey("accountTargets")
                ? (Map<String, Map<String, Number>>) v.get("accountTargets") : current.getAccountTargets();
        boolean ownLimit = "account".equals(scope) && targets != null
                && targets.values().stream().anyMatch(t -> t != null && t.get("dailySpendLimit") != null
                        && t.get("dailySpendLimit").doubleValue() > 0);
        if ((has.test("killSwitchEnabled") || has.test("dailySpendLimit") || has.test("killScope") || has.test("accountTargets"))
                && !e.containsKey("dailySpendLimit") && killOn && !(limit > 0) && !ownLimit)
            e.put("dailySpendLimit", "account".equals(scope)
                    ? "Hãy nhập mức chung hoặc đặt mức riêng cho ít nhất một tài khoản (Cài đặt → Mục tiêu) để bật dừng khẩn"
                    : "Hãy nhập mức chi tiêu tối đa mỗi ngày (lớn hơn 0) để bật dừng khẩn");
        if (has.test("mock") || has.test("dryRun") || has.test("adAccountId") || has.test("adAccountIds") || has.test("accessToken")) {
            boolean mock = v.containsKey("mock") ? (boolean) v.get("mock") : current.isMock();
            String token = v.containsKey("accessToken") ? (String) v.get("accessToken") : current.getAccessToken();
            @SuppressWarnings("unchecked")
            List<String> ids = v.containsKey("adAccountIds") ? (List<String>) v.get("adAccountIds") : current.accountIds();
            if (!mock && (token == null || token.isEmpty() || ids.isEmpty()))
                e.put("mock", "Cần kết nối Facebook (token và tài khoản quảng cáo) trước khi dùng dữ liệu thật.");
        }
        return c.done(v);
    }
}
