package com.fbads.engine.state;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, String> {
    @Modifying
    @Transactional
    @Query("delete from ScheduleRun r where r.runDate <> :today")
    int deleteOtherDays(String today);
}
