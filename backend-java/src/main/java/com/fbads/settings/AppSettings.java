package com.fbads.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fbads.common.JsonConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cài đặt của tool: bảng app_settings chỉ có 1 dòng (id = 1). Tên trường khớp tên khoá JSON của giao diện
 * (settings.mock, settings.dryRun, …) nên Jackson đọc/ghi thẳng được.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings {
    @Id
    @JsonIgnore
    private Integer id = 1;

    /** Khoá lạc quan: 2 nơi cùng sửa thì nơi lưu sau bị từ chối thay vì âm thầm ghi đè. */
    @Version
    @JsonIgnore
    private Integer version;

    private boolean mock = true;             // true = dữ liệu giả để dùng thử, không chạm vào Facebook
    private boolean dryRun = true;           // true = tự động hoá chỉ ghi log, không thực thi thật
    private String accessToken = "";
    private String adAccountId = "";         // tài khoản đầu tiên trong adAccountIds (giữ cho phần cũ)
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> adAccountIds = new ArrayList<>();
    private String fbAppId = "";
    private String fbAppSecret = "";
    private String fbConfigId = "";
    private String apiVersion = "v21.0";
    private String timezone = "Asia/Ho_Chi_Minh";
    private String resultAction = "purchase";
    private int ruleIntervalMin = 15;
    private String telegramToken = "";
    @Column(name = "telegram_chat_id")
    private String telegramChatId = "";
    private String reportTime = "08:00";
    private String passwordHash = "";
    private boolean skipLearning = true;
    private int dailyChangeCapPct = 30;
    private boolean killSwitchEnabled = false;
    private long dailySpendLimit = 0;
    private String killScope = "total";
    @Convert(converter = JsonConverters.TargetsMap.class)
    private Map<String, Map<String, Number>> accountTargets = new LinkedHashMap<>();

    /** Chế độ đang chạy: mock (dữ liệu giả) / dry (chạy thử) / live (thật) */
    @JsonIgnore
    public String mode() { return mock ? "mock" : dryRun ? "dry" : "live"; }

    /** Chạy thử trên dữ liệu thật: tự động hoá chỉ ghi nhật ký */
    @JsonIgnore
    public boolean isDry() { return dryRun && !mock; }

    /** Các tài khoản quảng cáo đang quản lý, bỏ tiền tố act_ và trùng lặp (như accountIds() của lib/fb.js) */
    @JsonIgnore
    public List<String> accountIds() {
        List<String> raw = adAccountIds != null && !adAccountIds.isEmpty() ? adAccountIds
                : adAccountId != null && !adAccountId.isBlank() ? List.of(adAccountId) : List.of();
        return raw.stream().map(x -> x.trim().replaceFirst("(?i)^act_", "")).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** Mục tiêu (cpa / roas / dailySpendLimit) của một tài khoản; 0 = chưa đặt */
    public double target(String accountId, String key) {
        Map<String, Number> t = accountTargets == null ? null : accountTargets.get(accountId);
        Number n = t == null ? null : t.get(key);
        return n == null ? 0 : n.doubleValue();
    }

    // ----- getter / setter (Jackson + JPA) -----
    public Integer getId() { return id; }
    public Integer getVersion() { return version; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getAdAccountId() { return adAccountId; }
    public void setAdAccountId(String adAccountId) { this.adAccountId = adAccountId; }
    public List<String> getAdAccountIds() { return adAccountIds; }
    public void setAdAccountIds(List<String> adAccountIds) { this.adAccountIds = adAccountIds; }
    public String getFbAppId() { return fbAppId; }
    public void setFbAppId(String fbAppId) { this.fbAppId = fbAppId; }
    public String getFbAppSecret() { return fbAppSecret; }
    public void setFbAppSecret(String fbAppSecret) { this.fbAppSecret = fbAppSecret; }
    public String getFbConfigId() { return fbConfigId; }
    public void setFbConfigId(String fbConfigId) { this.fbConfigId = fbConfigId; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getResultAction() { return resultAction; }
    public void setResultAction(String resultAction) { this.resultAction = resultAction; }
    public int getRuleIntervalMin() { return ruleIntervalMin; }
    public void setRuleIntervalMin(int ruleIntervalMin) { this.ruleIntervalMin = ruleIntervalMin; }
    public String getTelegramToken() { return telegramToken; }
    public void setTelegramToken(String telegramToken) { this.telegramToken = telegramToken; }
    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
    public String getReportTime() { return reportTime; }
    public void setReportTime(String reportTime) { this.reportTime = reportTime; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isSkipLearning() { return skipLearning; }
    public void setSkipLearning(boolean skipLearning) { this.skipLearning = skipLearning; }
    public int getDailyChangeCapPct() { return dailyChangeCapPct; }
    public void setDailyChangeCapPct(int dailyChangeCapPct) { this.dailyChangeCapPct = dailyChangeCapPct; }
    public boolean isKillSwitchEnabled() { return killSwitchEnabled; }
    public void setKillSwitchEnabled(boolean killSwitchEnabled) { this.killSwitchEnabled = killSwitchEnabled; }
    public long getDailySpendLimit() { return dailySpendLimit; }
    public void setDailySpendLimit(long dailySpendLimit) { this.dailySpendLimit = dailySpendLimit; }
    public String getKillScope() { return killScope; }
    public void setKillScope(String killScope) { this.killScope = killScope; }
    public Map<String, Map<String, Number>> getAccountTargets() { return accountTargets; }
    public void setAccountTargets(Map<String, Map<String, Number>> accountTargets) { this.accountTargets = accountTargets; }
}
