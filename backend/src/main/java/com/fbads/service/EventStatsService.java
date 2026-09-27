package com.fbads.service;

import com.fbads.engine.EngineClock;
import com.fbads.entity.EventStat;
import com.fbads.event.AppEvent;
import com.fbads.repository.EventStatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Consumer "thống kê": mỗi dòng nhật ký mới (log.created) = một thao tác, đếm theo ngày (múi giờ trong Cài đặt) và nguồn.
 * Kafka có thể giao một sự kiện 2 lần (consumer chết trước khi kịp báo đã đọc), nên ghi mã sự kiện đã đếm
 * vào event_stats_seen trong cùng transaction: nhận lại thì bỏ qua ("idempotent consumer").
 */
@Service
public class EventStatsService {
    private final EventStatRepository repo;
    private final EngineClock clock;
    private final TransactionTemplate tx;

    public EventStatsService(EventStatRepository repo, EngineClock clock, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.clock = clock;
        // REQUIRES_NEW: luôn một transaction riêng, kể cả khi được gọi lúc transaction khác vừa commit
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Chỉ log.created mới mở transaction; sự kiện khác (camp đổi, xong một lượt…) bỏ qua ngay */
    public void handle(AppEvent e) {
        if (!AppEvent.LOG_CREATED.equals(e.type()) || e.data() == null) return;
        String kind = e.data().path("kind").asString("");
        String source = kind.isEmpty() ? "unknown" : kind.length() > 20 ? kind.substring(0, 20) : kind;
        String day = Instant.ofEpochMilli(e.at()).atZone(clock.zone()).toLocalDate().toString();
        tx.executeWithoutResult(status -> {
            if (repo.markSeen(e.id(), day) == 0) return; // đã đếm sự kiện này
            repo.increment(day, source);
        });
    }

    public List<EventStat> ofDay(String day) { return repo.findByKeyDay(day); }

    /** Mã sự kiện đã đếm chỉ cần giữ vài ngày (Kafka không giao lại sự kiện cũ như vậy) */
    public void cleanup(String before) { repo.forgetSeenBefore(before); }
}
