package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Theo cặp rule + camp: lần tác động gần nhất (thời gian nghỉ) và hạn tạm hoãn sau khi người dùng hoàn tác. */
@Entity
@Table(name = "rule_marks")
public class RuleMark {
    @EmbeddedId
    private RuleObjKey key;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc (config/TenantConfig) */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    @JsonIgnore
    private Long workspaceId;
    private Long lastRunMs;
    private Long holdUntilMs;
    /** Rule tăng theo bậc: bậc đã chạy (0 = bậc 1) trong ngày ladderDate, lúc ladderAtMs */
    private String ladderDate;
    private Integer ladderStep;
    private Long ladderAtMs;

    protected RuleMark() {}

    public RuleMark(RuleObjKey key) { this.key = key; }

    public RuleObjKey getKey() { return key; }
    public long lastRun() { return lastRunMs == null ? 0 : lastRunMs; }
    public void setLastRunMs(Long v) { this.lastRunMs = v; }
    public long holdUntil() { return holdUntilMs == null ? 0 : holdUntilMs; }
    public void setHoldUntilMs(Long v) { this.holdUntilMs = v; }
    /** Bậc đã chạy trong ngày `date` (-1 = chưa bậc nào) */
    public int ladderStep(String date) { return date.equals(ladderDate) && ladderStep != null ? ladderStep : -1; }
    public long ladderAt(String date) { return date.equals(ladderDate) && ladderAtMs != null ? ladderAtMs : 0; }
    public void setLadder(String date, int step, long at) { this.ladderDate = date; this.ladderStep = step; this.ladderAtMs = at; }
}
