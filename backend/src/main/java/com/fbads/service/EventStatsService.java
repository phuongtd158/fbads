package com.fbads.service;

import com.fbads.engine.EngineClock;
import com.fbads.entity.EventStat;
import com.fbads.event.AppEvent;
import com.fbads.repository.EventStatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    public EventStatsService(EventStatRepository repo, EngineClock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** REQUIRES_NEW: luôn một transaction riêng, kể cả khi được gọi lúc transaction khác vừa commit */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(AppEvent e) {
        if (!AppEvent.LOG_CREATED.equals(e.type()) || e.data() == null) return;
        String source = e.data().path("kind").asString("");
        if (source.isEmpty()) source = "unknown";
        if (source.length() > 20) source = source.substring(0, 20);
        String day = Instant.ofEpochMilli(e.at()).atZone(clock.zone()).toLocalDate().toString();
        if (repo.markSeen(e.id(), day) == 0) return; // đã đếm sự kiện này
        repo.increment(day, source);
    }

    public List<EventStat> ofDay(String day) { return repo.findByKeyDay(day); }

    /** Mã sự kiện đã đếm chỉ cần giữ vài ngày (Kafka không giao lại sự kiện cũ như vậy) */
    public void cleanup(String before) { repo.forgetSeenBefore(before); }
}
