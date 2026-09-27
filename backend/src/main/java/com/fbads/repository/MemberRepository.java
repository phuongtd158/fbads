package com.fbads.repository;

import com.fbads.entity.Role;
import com.fbads.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMember.Key> {
    List<WorkspaceMember> findByKeyUserIdOrderByKeyWorkspaceIdAsc(long userId);

    List<WorkspaceMember> findByKeyWorkspaceId(long workspaceId);

    long countByKeyWorkspaceIdAndRole(long workspaceId, Role role);
}
