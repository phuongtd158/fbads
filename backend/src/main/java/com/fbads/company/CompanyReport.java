package com.fbads.company;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fbads.common.JsonConverters;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Một bản báo cáo lên hệ thống công ty: 1 Team × 1 ngày × 1 mốc. Tên trường JSON giống bản Node (giao diện dùng chung).
 * status: pending (chờ gửi) | review (tự động gửi dừng vì số bất thường) | retry (chờ thử lại) | sent | exists (công ty đã có) | failed.
 */
@Entity
@Table(name = "company_reports")
public class CompanyReport {
    /** Báo cáo trên hệ thống công ty (lần lấy / gửi gần nhất), để hiện và so với số trên tool */
    public record Remote(String id, String status, Integer revision, boolean locked, Map<String, Long> metrics, String notes, String issue,
                         String resolution, String updatedAt, String syncedAt) {}

    @Id
    private String id;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    @JsonIgnore
    private Long workspaceId;
    @Column(insertable = false, updatable = false)
    @JsonIgnore
    private Long seq;
    private int dateRule = 3;
    private String teamId;
    private String teamCode = "";
    private String teamName = "";
    @Column(name = "report_date")
    private String date;
    private int slot;
    /** 7 số của form công ty; null = chưa nhập */
    @Convert(converter = JsonConverters.NullableLongMap.class)
    private Map<String, Long> metrics = new LinkedHashMap<>();
    /** Các số đã sửa tay (làm mới số Facebook không ghi đè) */
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> edited = new ArrayList<>();
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> campaigns;
    private String notes = "";
    private String issue = "";
    private String resolution = "";
    private String status = "pending";
    private String error = "";
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> reasons = new ArrayList<>();
    private int attempts;
    private Instant nextTryAt;
    private String sentAs;
    private Instant sentAt;
    @Convert(converter = RemoteConverter.class)
    private Remote remote;
    private String remoteId;
    private String remoteStatus;
    private Instant remoteUpdatedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant builtAt;

    protected CompanyReport() {}

    public CompanyReport(String id, String teamId, String date, int slot, Instant createdAt) {
        this.id = id;
        this.teamId = teamId;
        this.date = date;
        this.slot = slot;
        this.createdAt = createdAt;
    }

    public static class RemoteConverter implements AttributeConverter<Remote, String> {
        private static final JsonMapper M = JsonMapper.builder().build();

        @Override
        public String convertToDatabaseColumn(Remote v) { return v == null ? null : M.writeValueAsString(v); }

        @Override
        public Remote convertToEntityAttribute(String s) {
            return s == null || s.isBlank() ? null : M.readValue(s, new TypeReference<Remote>() {});
        }
    }

    /** Đã có trên công ty mà chưa khoá → bấm "Cập nhật lên công ty" được */
    @JsonIgnore
    public boolean isOnRemote() { return "sent".equals(status) || "exists".equals(status); }

    public String getId() { return id; }
    public int getDateRule() { return dateRule; }
    public String getTeamId() { return teamId; }
    public String getTeamCode() { return teamCode; }
    public void setTeamCode(String v) { this.teamCode = v; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String v) { this.teamName = v; }
    @JsonProperty("date")
    public String getDate() { return date; }
    public int getSlot() { return slot; }
    public Map<String, Long> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Long> v) { this.metrics = v; }
    public List<String> getEdited() { return edited == null ? List.of() : edited; }
    public void setEdited(List<String> v) { this.edited = v; }
    public List<String> getCampaigns() { return campaigns; }
    public void setCampaigns(List<String> v) { this.campaigns = v; }
    public String getNotes() { return notes == null ? "" : notes; }
    public void setNotes(String v) { this.notes = v; }
    public String getIssue() { return issue == null ? "" : issue; }
    public void setIssue(String v) { this.issue = v; }
    public String getResolution() { return resolution == null ? "" : resolution; }
    public void setResolution(String v) { this.resolution = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getError() { return error == null ? "" : error; }
    public void setError(String v) { this.error = v; }
    public List<String> getReasons() { return reasons == null ? List.of() : reasons; }
    public void setReasons(List<String> v) { this.reasons = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public Instant getNextTryAt() { return nextTryAt; }
    public void setNextTryAt(Instant v) { this.nextTryAt = v; }
    public String getSentAs() { return sentAs; }
    public void setSentAs(String v) { this.sentAs = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public Remote getRemote() { return remote; }
    public void setRemote(Remote v) { this.remote = v; }
    public String getRemoteId() { return remoteId; }
    public void setRemoteId(String v) { this.remoteId = v; }
    public String getRemoteStatus() { return remoteStatus; }
    public void setRemoteStatus(String v) { this.remoteStatus = v; }
    public Instant getRemoteUpdatedAt() { return remoteUpdatedAt; }
    public void setRemoteUpdatedAt(Instant v) { this.remoteUpdatedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getBuiltAt() { return builtAt; }
    public void setBuiltAt(Instant v) { this.builtAt = v; }
}
