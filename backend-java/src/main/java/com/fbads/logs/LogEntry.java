package com.fbads.logs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.common.JsonConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;

/**
 * Một dòng nhật ký. Các phần lồng nhau (target, action, before, after, condition, error, undone) giữ dạng JSON tự do
 * như bản Node: có khoá nào thì ghi khoá đó (giao diện và hoàn tác phân biệt "không có" với "null").
 */
@Entity
@Table(name = "logs")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogEntry {
    @Id
    private String id;
    @Column(insertable = false, updatable = false)
    @JsonIgnore
    private Long seq;
    private Instant ts;
    private String kind;          // manual | schedule | rule | system | undo
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
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> target;
    @Column(name = "action_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> action;
    @Column(name = "before_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> before;
    @Column(name = "after_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> after;
    @Column(name = "condition_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> condition;
    @Column(name = "error_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> error;
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> undone;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
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
    public Map<String, Object> getTarget() { return target; }
    public void setTarget(Map<String, Object> target) { this.target = target; }
    public Map<String, Object> getAction() { return action; }
    public void setAction(Map<String, Object> action) { this.action = action; }
    public Map<String, Object> getBefore() { return before; }
    public void setBefore(Map<String, Object> before) { this.before = before; }
    public Map<String, Object> getAfter() { return after; }
    public void setAfter(Map<String, Object> after) { this.after = after; }
    public Map<String, Object> getCondition() { return condition; }
    public void setCondition(Map<String, Object> condition) { this.condition = condition; }
    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> error) { this.error = error; }
    public Map<String, Object> getUndone() { return undone; }
    public void setUndone(Map<String, Object> undone) { this.undone = undone; }

    /** Thành công? (thiếu ok = thành công, như bản Node). Không đặt tên isOk để Jackson không nhầm với thuộc tính "ok". */
    public boolean succeeded() { return !Boolean.FALSE.equals(ok); }
}
