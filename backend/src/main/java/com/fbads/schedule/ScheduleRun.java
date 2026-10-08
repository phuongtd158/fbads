package com.fbads.schedule;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** Một mốc lịch đã chạy: khoá "id lịch:ngày:giờ". Persistable.isNew() = true để save() luôn là INSERT (trùng khoá → lỗi). */
@Entity
@Table(name = "schedule_runs")
public class ScheduleRun implements Persistable<String> {
    @Id
    private String runKey;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc (config/TenantConfig) */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    @JsonIgnore
    private Long workspaceId;
    private String runDate;
    private Instant createdAt;

    protected ScheduleRun() {}

    public ScheduleRun(String runKey, String runDate) {
        this.runKey = runKey;
        this.runDate = runDate;
        this.createdAt = Instant.now();
    }

    @Override
    public String getId() { return runKey; }

    @Override
    public boolean isNew() { return true; }

    public String getRunDate() { return runDate; }
}
