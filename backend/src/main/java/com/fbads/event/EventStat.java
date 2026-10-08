package com.fbads.event;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Số thao tác trong một ngày theo nguồn (schedule | rule | manual | undo | system). Consumer "thống kê" ghi (EventStatsService). */
@Entity
@Table(name = "event_stats")
public class EventStat {
    @Embeddable
    public record Key(@Column(name = "workspace_id") Long workspaceId, @Column(name = "day") String day,
            @Column(name = "source") String source) {}

    @EmbeddedId
    private Key key;
    private int actions;

    protected EventStat() {}

    public Key getKey() { return key; }
    public int getActions() { return actions; }
}
