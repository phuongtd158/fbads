package com.fbads.repository;

import com.fbads.entity.EventStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** SQL chạy được trên cả MySQL 8 và MariaDB (INSERT IGNORE, ON DUPLICATE KEY UPDATE). */
public interface EventStatRepository extends JpaRepository<EventStat, EventStat.Key> {
    List<EventStat> findByKeyDay(String day);

    /** Đánh dấu sự kiện đã đếm. Trả về 0 nếu đã đếm rồi (nhận trùng). */
    @Modifying
    @Query(value = "INSERT IGNORE INTO event_stats_seen (event_id, day) VALUES (:eventId, :day)", nativeQuery = true)
    int markSeen(String eventId, String day);

    /** Cộng 1 thao tác cho (ngày, nguồn); chưa có dòng thì tạo mới */
    @Modifying
    @Query(value = "INSERT INTO event_stats (day, source, actions) VALUES (:day, :source, 1) ON DUPLICATE KEY UPDATE actions = actions + 1",
            nativeQuery = true)
    int increment(String day, String source);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM event_stats_seen WHERE day < :before", nativeQuery = true)
    int forgetSeenBefore(String before);
}
