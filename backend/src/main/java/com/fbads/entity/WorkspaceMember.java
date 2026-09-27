package com.fbads.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/** Người dùng thuộc workspace với một vai trò. */
@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {
    @Embeddable
    public record Key(@Column(name = "workspace_id") Long workspaceId, @Column(name = "user_id") Long userId) {}

    @EmbeddedId
    private Key key;
    @Enumerated(EnumType.STRING)
    private Role role;
    private Instant createdAt;

    protected WorkspaceMember() {}

    public WorkspaceMember(long workspaceId, long userId, Role role) {
        this.key = new Key(workspaceId, userId);
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Key getKey() { return key; }
    public long workspaceId() { return key.workspaceId(); }
    public long userId() { return key.userId(); }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
