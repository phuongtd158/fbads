package com.fbads.company;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fbads.common.JsonConverters;
import com.fbads.common.SecretConverter;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cài đặt báo cáo lên hệ thống công ty của một workspace (bảng company_config, id = id của workspace).
 * Mật khẩu web công ty mã hoá trong DB và không bao giờ gửi về giao diện (publicView chỉ có has_password).
 */
@Entity
@Table(name = "company_config")
public class CompanyConfig {
    public static final String DEFAULT_BASE_URL = "https://mkt.companyos.site";

    /** Một Team của công ty và cách chọn chiến dịch của Team: đúng tài khoản quảng cáo VÀ tên chứa một trong các từ khoá */
    public record Team(String id, String code, String name, List<String> accountIds, String match) {}

    @Id
    @JsonIgnore
    private Long id;
    private boolean enabled = false;
    private String mode = "approve";             // preview | approve | auto
    @Convert(converter = JsonConverters.IntList.class)
    private List<Integer> slots = new ArrayList<>(List.of(9, 12, 17, 22));
    private int leadMin = 0;                     // làm báo cáo sớm hơn mốc n phút
    private String baseUrl = DEFAULT_BASE_URL;
    private String email = "";
    @Convert(converter = SecretConverter.class)  // mã hoá trong DB khi có SECRET_KEY
    @JsonIgnore
    private String password = "";
    @Convert(converter = TeamList.class)
    private List<Team> teams = new ArrayList<>();

    protected CompanyConfig() {}

    public CompanyConfig(long workspaceId) { this.id = workspaceId; }

    public static class TeamList implements AttributeConverter<List<Team>, String> {
        private static final JsonMapper M = JsonMapper.builder().build();

        @Override
        public String convertToDatabaseColumn(List<Team> v) { return v == null ? null : M.writeValueAsString(v); }

        @Override
        public List<Team> convertToEntityAttribute(String s) {
            return s == null || s.isBlank() ? new ArrayList<>() : M.readValue(s, new TypeReference<List<Team>>() {});
        }
    }

    /** Cài đặt gửi về giao diện: không bao giờ lộ mật khẩu */
    public Map<String, Object> publicView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled); m.put("mode", mode); m.put("slots", slots); m.put("leadMin", leadMin); m.put("baseUrl", baseUrl);
        m.put("email", email); m.put("teams", teams); m.put("has_password", password != null && !password.isEmpty());
        return m;
    }

    public boolean canSend() { return "approve".equals(mode) || "auto".equals(mode); }

    public Long getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public List<Integer> getSlots() { return slots == null ? List.of() : slots; }
    public void setSlots(List<Integer> v) { this.slots = v; }
    public int getLeadMin() { return leadMin; }
    public void setLeadMin(int v) { this.leadMin = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPassword() { return password == null ? "" : password; }
    public void setPassword(String v) { this.password = v; }
    public List<Team> getTeams() { return teams == null ? List.of() : teams; }
    public void setTeams(List<Team> v) { this.teams = v; }
}
