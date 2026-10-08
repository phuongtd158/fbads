package com.fbads.log;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface LogRepository extends JpaRepository<LogEntry, String> {
    /** Mới nhất trước */
    List<LogEntry> findAllByOrderBySeqDesc(Limit limit);

    List<LogEntry> findByKindOrderBySeqDesc(LogKind kind);

    List<LogEntry> findByTsGreaterThanEqualOrderBySeqAsc(Instant ts);

    /** SQL thuần không qua bộ lọc @TenantId nên phải ghi rõ workspace */
    @Query(value = "SELECT seq FROM logs WHERE workspace_id = :workspaceId ORDER BY seq DESC LIMIT 1 OFFSET :keep", nativeQuery = true)
    Long seqAtOffset(long workspaceId, int keep);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM logs WHERE workspace_id = :workspaceId AND seq <= :seq", nativeQuery = true)
    int deleteUpTo(long workspaceId, long seq);
}
