package com.fbads.settings;

import com.fbads.common.JsNumber;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Body của POST /api/settings: chỉ gửi các khoá muốn đổi (PATCH). Luật kiểm tra nằm ở SettingsValidator.
 *
 * Record không phân biệt được "không gửi" với "gửi null/rỗng" (vd. xoá giờ báo cáo), nên đây là lớp có setter:
 * Jackson gọi setter cho mọi khoá có trong JSON, kể cả khi giá trị null, và setter ghi tên khoá vào `sent`.
 * SettingsValidator chỉ xét và chỉ ghi các khoá has(khoá) = true.
 */
public final class SettingsPatch {
    /** Mục tiêu của 1 tài khoản; 0 hoặc trống = chưa đặt */
    public record AccountTarget(@JsNumber Double cpa, @JsNumber Double roas, @JsNumber Double dailySpendLimit) {}

    /** Tên các khoá có trong JSON gửi lên */
    private final Set<String> sent = new LinkedHashSet<>();

    /** Múi giờ, vd. Asia/Ho_Chi_Minh */
    private String timezone;
    /** Chu kỳ kiểm tra rule (phút) */
    private Double ruleIntervalMin;
    /** Giờ gửi báo cáo "HH:MM", "" = tắt */
    private String reportTime;
    /** Các tài khoản quảng cáo quản lý */
    private List<String> adAccountIds;
    /** Bản cũ: chỉ 1 tài khoản */
    private String adAccountId;
    /** Token Facebook; "" = giữ token cũ */
    private String accessToken;
    /** Loại kết quả tính là 1 chuyển đổi */
    private String resultAction;
    /** Phiên bản Graph API, vd. v21.0 */
    private String apiVersion;
    /** Bỏ qua camp đang học */
    private Boolean skipLearning;
    /** Giới hạn % đổi ngân sách mỗi ngày */
    private Double dailyChangeCapPct;
    /** Dừng khẩn khi chi tiêu vượt mức */
    private Boolean killSwitchEnabled;
    /** Mức chi tiêu tối đa mỗi ngày; "" = 0 */
    private Double dailySpendLimit;
    /** "total" | "account" */
    private String killScope;
    /** Mục tiêu theo từng tài khoản: { [id]: { cpa, roas, dailySpendLimit } } */
    private Map<String, AccountTarget> accountTargets;
    /** Cảnh báo bất thường: tài khoản có vấn đề / quảng cáo bị từ chối / chi tiêu tăng vọt */
    private Boolean alertAccount, alertDisapproved, alertSpike;
    /** Tăng vọt: tăng hơn bao nhiêu % so với cùng giờ hôm qua (10–1000) */
    private Double spikePct;
    /** Tăng vọt: chỉ báo khi hôm nay đã chi từ mức này; "" = 0 */
    private Double spikeMinSpend;
    /** Báo cáo tuần (gửi qua các kênh thông báo nhận Báo cáo) */
    private Boolean weeklyReport;
    /** Dùng dữ liệu giả */
    private Boolean mock;
    /** Chạy thử, không đổi gì trên Facebook */
    private Boolean dryRun;

    /** Khoá này có được gửi lên không (kể cả gửi null) — như Object.hasOwn(patch, key) của bản Node */
    public boolean has(String key) { return sent.contains(key); }

    /** Chỉ in tên khoá, không in giá trị (token) — log gọi hàm dùng toString */
    @Override
    public String toString() { return "SettingsPatch" + sent; }

    public String timezone() { return timezone; }
    public Double ruleIntervalMin() { return ruleIntervalMin; }
    public String reportTime() { return reportTime; }
    public List<String> adAccountIds() { return adAccountIds; }
    public String adAccountId() { return adAccountId; }
    public String accessToken() { return accessToken; }
    public String resultAction() { return resultAction; }
    public String apiVersion() { return apiVersion; }
    public Boolean skipLearning() { return skipLearning; }
    public Double dailyChangeCapPct() { return dailyChangeCapPct; }
    public Boolean killSwitchEnabled() { return killSwitchEnabled; }
    public Double dailySpendLimit() { return dailySpendLimit; }
    public String killScope() { return killScope; }
    public Map<String, AccountTarget> accountTargets() { return accountTargets; }
    public Boolean alertAccount() { return alertAccount; }
    public Boolean alertDisapproved() { return alertDisapproved; }
    public Boolean alertSpike() { return alertSpike; }
    public Double spikePct() { return spikePct; }
    public Double spikeMinSpend() { return spikeMinSpend; }
    public Boolean weeklyReport() { return weeklyReport; }
    public Boolean mock() { return mock; }
    public Boolean dryRun() { return dryRun; }

    public void setTimezone(String v) { timezone = v; sent.add("timezone"); }

    @JsNumber
    public void setRuleIntervalMin(Double v) { ruleIntervalMin = v; sent.add("ruleIntervalMin"); }

    public void setReportTime(String v) { reportTime = v; sent.add("reportTime"); }



    public void setAdAccountIds(List<String> v) { adAccountIds = v; sent.add("adAccountIds"); }

    public void setAdAccountId(String v) { adAccountId = v; sent.add("adAccountId"); }

    public void setAccessToken(String v) { accessToken = v; sent.add("accessToken"); }

    public void setResultAction(String v) { resultAction = v; sent.add("resultAction"); }

    public void setApiVersion(String v) { apiVersion = v; sent.add("apiVersion"); }

    public void setSkipLearning(Boolean v) { skipLearning = v; sent.add("skipLearning"); }

    @JsNumber
    public void setDailyChangeCapPct(Double v) { dailyChangeCapPct = v; sent.add("dailyChangeCapPct"); }

    public void setKillSwitchEnabled(Boolean v) { killSwitchEnabled = v; sent.add("killSwitchEnabled"); }

    @JsNumber
    public void setDailySpendLimit(Double v) { dailySpendLimit = v; sent.add("dailySpendLimit"); }

    public void setKillScope(String v) { killScope = v; sent.add("killScope"); }

    public void setAccountTargets(Map<String, AccountTarget> v) { accountTargets = v; sent.add("accountTargets"); }

    public void setAlertAccount(Boolean v) { alertAccount = v; sent.add("alertAccount"); }

    public void setAlertDisapproved(Boolean v) { alertDisapproved = v; sent.add("alertDisapproved"); }

    public void setAlertSpike(Boolean v) { alertSpike = v; sent.add("alertSpike"); }

    @JsNumber
    public void setSpikePct(Double v) { spikePct = v; sent.add("spikePct"); }

    @JsNumber
    public void setSpikeMinSpend(Double v) { spikeMinSpend = v; sent.add("spikeMinSpend"); }

    public void setWeeklyReport(Boolean v) { weeklyReport = v; sent.add("weeklyReport"); }

    public void setMock(Boolean v) { mock = v; sent.add("mock"); }

    public void setDryRun(Boolean v) { dryRun = v; sent.add("dryRun"); }
}
