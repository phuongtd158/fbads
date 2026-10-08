package com.fbads.company;

import com.fbads.common.JsNumber;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Body của POST /api/company/config: chỉ gửi các khoá muốn đổi (như SettingsPatch, setter ghi lại khoá nào đã gửi).
 * Mật khẩu để trống = giữ mật khẩu cũ. Luật kiểm tra ở CompanyRules.validateConfig.
 */
public final class CompanyConfigPatch {
    /** Một Team: id của hệ thống công ty, mã/tên để hiển thị, tài khoản quảng cáo và từ khoá tên chiến dịch */
    public record TeamRequest(String id, String code, String name, List<String> accountIds, String match) {}

    private final Set<String> sent = new LinkedHashSet<>();
    private Boolean enabled;
    private String mode;
    /** Mốc báo cáo; số hoặc chuỗi số như Number() của JS */
    private List<Object> slots;
    @JsNumber
    private Double leadMin;
    private String baseUrl;
    private String email;
    private String password;
    private List<TeamRequest> teams;

    public boolean has(String key) { return sent.contains(key); }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean v) { enabled = v; sent.add("enabled"); }
    public String getMode() { return mode; }
    public void setMode(String v) { mode = v; sent.add("mode"); }
    public List<Object> getSlots() { return slots; }
    public void setSlots(List<Object> v) { slots = v; sent.add("slots"); }
    public Double getLeadMin() { return leadMin; }
    public void setLeadMin(Double v) { leadMin = v; sent.add("leadMin"); }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; sent.add("baseUrl"); }
    public String getEmail() { return email; }
    public void setEmail(String v) { email = v; sent.add("email"); }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; sent.add("password"); }
    public List<TeamRequest> getTeams() { return teams; }
    public void setTeams(List<TeamRequest> v) { teams = v; sent.add("teams"); }
}
