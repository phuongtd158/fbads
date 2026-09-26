package com.fbads.engine.state;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Theo cặp rule + camp: lần tác động gần nhất (thời gian nghỉ) và hạn tạm hoãn sau khi người dùng hoàn tác. */
@Entity
@Table(name = "rule_marks")
public class RuleMark {
    @EmbeddedId
    private RuleObjKey key;
    private Long lastRunMs;
    private Long holdUntilMs;

    protected RuleMark() {}

    public RuleMark(RuleObjKey key) { this.key = key; }

    public RuleObjKey getKey() { return key; }
    public long lastRun() { return lastRunMs == null ? 0 : lastRunMs; }
    public void setLastRunMs(Long v) { this.lastRunMs = v; }
    public long holdUntil() { return holdUntilMs == null ? 0 : holdUntilMs; }
    public void setHoldUntilMs(Long v) { this.holdUntilMs = v; }
}
