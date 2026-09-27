package com.fbads.repository;

import com.fbads.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
    @Query("select w.id from Workspace w order by w.id")
    List<Long> allIds();
}
