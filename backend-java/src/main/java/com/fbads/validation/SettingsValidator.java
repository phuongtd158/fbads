package com.fbads.validation;

import com.fbads.common.Json;
import com.fbads.settings.AppSettings;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Kiểm tra phần cài đặt người dùng gửi lên (bản Java của validateSettings trong shared/validate.mjs).
 * Chỉ các khoá có trong patch mới được xét và mới được ghi (value = map khoá → giá trị đã chuẩn hoá).
 */
public final class SettingsValidator {
    private SettingsValidator() {}

    public static Result<Map<String, Object>> validate(JsonNode patch, AppSettings current) {
        Result.Collector c = new Result.Collector();
        Map<String, String> e = c.e;
        Map<String, Object> v = new LinkedHashMap<>();
        java.util.function.Predicate<String> has = k -> Json.has(patch, k);

        if (has.test("timezone")) {
            String t = Json.str(patch.get("timezone")).trim();
            if (!Checks.isTimezone(t)) e.put("timezone", "Múi giờ không hợp lệ (ví dụ Asia/Ho_Chi_Minh)"); else v.put("timezone", t);
        }
        if (has.test("ruleIntervalMin")) {
            double n = Json.num(patch.get("ruleIntervalMin"));
            if (n != Math.rint(n) || n < Checks.INTERVAL_MIN || n > Checks.INTERVAL_MAX)
                e.put("ruleIntervalMin", "Chu kỳ kiểm tra rule từ " + Checks.INTERVAL_MIN + " đến " + Checks.INTERVAL_MAX + " phút");
            else v.put("ruleIntervalMin", (int) n);
        }
        if (has.test("reportTime")) {
            String t = Json.str(patch.get("reportTime"));
            if (!t.isEmpty() && !Checks.isTime(t)) e.put("reportTime", "Giờ báo cáo không hợp lệ (HH:MM)"); else v.put("reportTime", t);
        }
        if (has.test("telegramChatId")) {
            String ch = Json.str(patch.get("telegramChatId")).trim();
            String m = Checks.checkTelegramChats(ch);
            if (!m.isEmpty()) e.put("telegramChatId", m); else v.put("telegramChatId", String.join(", ", Checks.parseChatIds(ch)));
        }
        if (has.test("telegramToken")) {
            String t = Json.str(patch.get("telegramToken")).trim();
            String m = Checks.checkTelegramToken(t);
            if (!m.isEmpty()) e.put("telegramToken", m); else if (!t.isEmpty()) v.put("telegramToken", t);
        }
        // Tài khoản quảng cáo: quản lý được nhiều tài khoản. adAccountId = tài khoản đầu tiên, giữ cho phần cũ.
        if (has.test("adAccountIds") || has.test("adAccountId")) {
            List<String> raw = has.test("adAccountIds") ? Json.strings(patch.get("adAccountIds")) : List.of(Json.str(patch.get("adAccountId")));
            List<String> ids = new ArrayList<>(new LinkedHashSet<>(raw.stream().map(Checks::cleanAccountId).filter(s -> !s.isEmpty()).toList()));
            String bad = ids.stream().filter(a -> !Checks.checkAccountId(a).isEmpty()).findFirst().orElse(null);
            if (bad != null) e.put("adAccountId", Checks.checkAccountId(bad) + " — \"" + bad + "\"");
            else if (ids.size() > Checks.ACCOUNTS_MAX) e.put("adAccountId", "Tối đa " + Checks.ACCOUNTS_MAX + " tài khoản quảng cáo");
            else { v.put("adAccountIds", ids); v.put("adAccountId", ids.isEmpty() ? "" : ids.getFirst()); }
        }
        if (has.test("accessToken")) {
            String t = Json.str(patch.get("accessToken")).trim();
            if (!t.isEmpty()) {
                String m = Checks.checkToken(t);
                if (!m.isEmpty()) e.put("accessToken", m); else v.put("accessToken", t);
            }
        }
        if (has.test("resultAction")) {
            String r = Json.str(patch.get("resultAction")).trim();
            if (!r.matches("^[A-Za-z0-9_.]{2,100}$")) e.put("resultAction", "Loại kết quả không hợp lệ"); else v.put("resultAction", r);
        }
        if (has.test("apiVersion")) {
            String r = Json.str(patch.get("apiVersion")).trim();
            if (!r.matches("^v\\d+\\.\\d+$")) e.put("apiVersion", "Phiên bản API không hợp lệ (ví dụ v21.0)"); else v.put("apiVersion", r);
        }
        if (has.test("skipLearning")) v.put("skipLearning", Json.truthy(patch.get("skipLearning")));
        if (has.test("dailyChangeCapPct")) {
            double n = Json.num(patch.get("dailyChangeCapPct"));
            if (n != Math.rint(n) || n < Checks.CAP_PCT_MIN || n > Checks.CAP_PCT_MAX)
                e.put("dailyChangeCapPct", "Giới hạn thay đổi ngân sách mỗi ngày từ " + Checks.CAP_PCT_MIN + "% đến " + Checks.CAP_PCT_MAX + "%");
            else v.put("dailyChangeCapPct", (int) n);
        }
        if (has.test("killSwitchEnabled")) v.put("killSwitchEnabled", Json.truthy(patch.get("killSwitchEnabled")));
        if (has.test("dailySpendLimit")) {
            double n = Json.isBlank(patch.get("dailySpendLimit")) ? 0 : Json.num(patch.get("dailySpendLimit"));
            if (!Double.isFinite(n) || n < 0 || n > Checks.BUDGET_MAX) e.put("dailySpendLimit", "Mức chi tiêu tối đa mỗi ngày phải là số không âm");
            else v.put("dailySpendLimit", Math.round(n));
        }
        if (has.test("killScope")) {
            String k = Json.str(patch.get("killScope"));
            if (k.equals("account") || k.equals("total")) v.put("killScope", k); else e.put("killScope", "Phạm vi dừng khẩn không hợp lệ");
        }
        // Mục tiêu theo từng tài khoản: { [id]: { cpa, roas, dailySpendLimit } }. 0/trống = chưa đặt.
        if (has.test("accountTargets")) {
            JsonNode raw = patch.get("accountTargets");
            Map<String, Map<String, Number>> out = new LinkedHashMap<>();
            if (raw == null || !raw.isObject()) e.put("accountTargets", "Mục tiêu theo tài khoản không hợp lệ");
            else {
                Map<String, Double> cap = new LinkedHashMap<>();
                cap.put("cpa", Checks.BUDGET_MAX); cap.put("roas", 100.0); cap.put("dailySpendLimit", Checks.BUDGET_MAX);
                Map<String, String> label = Map.of("cpa", "CPA mục tiêu", "roas", "ROAS mục tiêu", "dailySpendLimit", "Mức dừng khẩn");
                outer:
                for (var entry : raw.properties()) {
                    String id = entry.getKey();
                    JsonNode t = entry.getValue();
                    String sid = id.length() > 20 ? id.substring(0, 20) : id;
                    if (!id.matches("^[A-Za-z0-9_]{1,40}$")) { e.put("accountTargets", "Mã tài khoản không hợp lệ: " + sid); break; }
                    Map<String, Number> o = new LinkedHashMap<>();
                    for (String k : cap.keySet()) {
                        JsonNode x = t == null ? null : t.get(k);
                        double n = Json.isBlank(x) ? 0 : Json.num(x);
                        if (!Double.isFinite(n) || n < 0 || n > cap.get(k)) {
                            e.put("accountTargets", label.get(k) + " của tài khoản " + sid + " phải là số không âm" + (k.equals("roas") ? " (tối đa 100)" : ""));
                            break outer;
                        }
                        if (n > 0) o.put(k, k.equals("roas") ? Math.round(n * 100) / 100.0 : Math.round(n));
                    }
                    if (!o.isEmpty()) out.put(id, o);
                    if (out.size() > Checks.ACCOUNTS_MAX) { e.put("accountTargets", "Tối đa " + Checks.ACCOUNTS_MAX + " tài khoản"); break; }
                }
            }
            if (!e.containsKey("accountTargets")) v.put("accountTargets", out);
        }
        if (has.test("mock")) v.put("mock", Json.truthy(patch.get("mock")));
        if (has.test("dryRun")) v.put("dryRun", Json.truthy(patch.get("dryRun")));

        // Giá trị sau khi áp dụng (để kiểm tra các điều kiện liên quan nhiều khoá)
        boolean killOn = v.containsKey("killSwitchEnabled") ? (boolean) v.get("killSwitchEnabled") : current.isKillSwitchEnabled();
        double limit = v.containsKey("dailySpendLimit") ? ((Number) v.get("dailySpendLimit")).doubleValue() : current.getDailySpendLimit();
        String scope = v.containsKey("killScope") ? (String) v.get("killScope") : current.getKillScope();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> targets = v.containsKey("accountTargets") ? (Map<String, Map<String, Number>>) v.get("accountTargets") : current.getAccountTargets();
        boolean ownLimit = "account".equals(scope) && targets != null
                && targets.values().stream().anyMatch(t -> t != null && t.get("dailySpendLimit") != null && t.get("dailySpendLimit").doubleValue() > 0);
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
            if (!mock && (token == null || token.isEmpty() || ids.isEmpty())) e.put("mock", "Cần kết nối Facebook (token và tài khoản quảng cáo) trước khi dùng dữ liệu thật.");
        }
        return c.done(v);
    }
}
