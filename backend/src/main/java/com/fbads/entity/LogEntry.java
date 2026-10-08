package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * Một dòng nhật ký. Các phần lồng nhau là record riêng, lưu thành cột JSON: target (camp nào), action (làm gì),
 * before (trước đó), after (sau đó), condition (điều kiện đã khớp), error (lỗi), undone (đã hoàn tác chưa).
 */
@Entity
@Table(name = "logs")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogEntry {
    @Id
    private String id;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc (config/TenantConfig) */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    @JsonIgnore
    private Long workspaceId;
    @Column(insertable = false, updatable = false)
    @JsonIgnore
    private Long seq;
    private Instant ts;
    @Convert(converter = LogKind.Converter.class)
    private LogKind kind;
    private String source;
    private String name;
    private String detail;
    private Boolean ok;
    private String mode;          // mock | dry | live
    private Boolean dry;
    private Boolean skipped;
    private String refId;
    private String refName;
    private String refLogId;
    @Convert(converter = LogTarget.Converter.class)
    private LogTarget target;
    @Column(name = "action_json")
    @Convert(converter = LogAction.Converter.class)
    private LogAction action;
    @Column(name = "before_json")
    @Convert(converter = LogSnapshot.Converter.class)
    private LogSnapshot before;
    @Column(name = "after_json")
    @Convert(converter = LogChange.Converter.class)
    private LogChange after;
    @Column(name = "condition_json")
    @Convert(converter = LogCondition.Converter.class)
    private LogCondition condition;
    @Column(name = "error_json")
    @Convert(converter = LogError.Converter.class)
    private LogError error;
    @Convert(converter = LogUndone.Converter.class)
    private LogUndone undone;

    /** Dòng nhật ký mới: nguồn (kind), chữ hiện ở cột Nguồn (source) và tên camp/việc (name). Các trường khác đặt bằng setter. */
    public static LogEntry of(LogKind kind, String source, String name) {
        LogEntry e = new LogEntry();
        e.kind = kind;
        e.source = source;
        e.name = name;
        return e;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public LogKind getKind() { return kind; }
    public void setKind(LogKind kind) { this.kind = kind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getOk() { return ok; }
    public void setOk(Boolean ok) { this.ok = ok; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Boolean getDry() { return dry; }
    public void setDry(Boolean dry) { this.dry = dry; }
    public Boolean getSkipped() { return skipped; }
    public void setSkipped(Boolean skipped) { this.skipped = skipped; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public String getRefName() { return refName; }
    public void setRefName(String refName) { this.refName = refName; }
    public String getRefLogId() { return refLogId; }
    public void setRefLogId(String refLogId) { this.refLogId = refLogId; }
    public LogTarget getTarget() { return target; }
    public void setTarget(LogTarget target) { this.target = target; }
    public LogAction getAction() { return action; }
    public void setAction(LogAction action) { this.action = action; }
    public LogSnapshot getBefore() { return before; }
    public void setBefore(LogSnapshot before) { this.before = before; }
    public LogChange getAfter() { return after; }
    public void setAfter(LogChange after) { this.after = after; }
    public LogCondition getCondition() { return condition; }
    public void setCondition(LogCondition condition) { this.condition = condition; }
    public LogError getError() { return error; }
    public void setError(LogError error) { this.error = error; }
    public LogUndone getUndone() { return undone; }
    public void setUndone(LogUndone undone) { this.undone = undone; }

    /** Thành công? (thiếu ok = thành công, như bản Node). Không đặt tên isOk để Jackson không nhầm với thuộc tính "ok". */
    public boolean succeeded() { return !Boolean.FALSE.equals(ok); }
}
