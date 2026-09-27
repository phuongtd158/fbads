package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Camp do rule tắt, hẹn bật lại vào giờ resumeAt của một ngày sau ngày tắt. */
@Entity
@Table(name = "rule_resumes")
public class RuleResume {
    @EmbeddedId
    private RuleObjKey key;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc (config/TenantConfig) */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    @JsonIgnore
    private Long workspaceId;
    private String offDate;

    protected RuleResume() {}

    public RuleResume(RuleObjKey key, String offDate) {
        this.key = key;
        this.offDate = offDate;
    }

    public RuleObjKey getKey() { return key; }
    public String getOffDate() { return offDate; }
}
