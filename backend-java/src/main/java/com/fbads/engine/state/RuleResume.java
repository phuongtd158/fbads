package com.fbads.engine.state;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Camp do rule tắt, hẹn bật lại vào giờ resumeAt của một ngày sau ngày tắt. */
@Entity
@Table(name = "rule_resumes")
public class RuleResume {
    @EmbeddedId
    private RuleObjKey key;
    private String offDate;

    protected RuleResume() {}

    public RuleResume(RuleObjKey key, String offDate) {
        this.key = key;
        this.offDate = offDate;
    }

    public RuleObjKey getKey() { return key; }
    public String getOffDate() { return offDate; }
}
