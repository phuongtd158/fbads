package com.fbads.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DailyMarkRepository extends JpaRepository<DailyMark, DailyMark.Key> {
    @Modifying
    @Transactional
    @Query("delete from DailyMark m where m.key.workspaceId = :workspaceId and m.key.day < :before")
    int deleteBefore(long workspaceId, String before);
}
