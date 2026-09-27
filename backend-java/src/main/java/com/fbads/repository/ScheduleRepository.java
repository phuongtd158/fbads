package com.fbads.repository;

import com.fbads.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    List<Schedule> findAllByOrderBySeqAsc();
}
