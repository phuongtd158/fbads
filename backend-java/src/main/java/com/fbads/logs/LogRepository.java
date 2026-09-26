package com.fbads.logs;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface LogRepository extends JpaRepository<LogEntry, String> {
    /** Mới nhất trước */
    List<LogEntry> findAllByOrderBySeqDesc(Limit limit);

    List<LogEntry> findByKindOrderBySeqDesc(String kind);

    @Query(value = "SELECT seq FROM logs ORDER BY seq DESC LIMIT 1 OFFSET :keep", nativeQuery = true)
    Long seqAtOffset(int keep);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM logs WHERE seq <= :seq", nativeQuery = true)
    int deleteUpTo(long seq);
}
