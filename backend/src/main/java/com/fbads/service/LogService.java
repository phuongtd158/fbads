package com.fbads.service;

import com.fbads.common.Ids;
import com.fbads.entity.LogEntry;
import com.fbads.event.AppEvent;
import com.fbads.event.EventBus;
import com.fbads.repository.LogRepository;
import com.fbads.security.WorkspaceContext;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Ghi nhật ký và giữ lại 1000 dòng mới nhất của mỗi workspace (như bản Node). */
@Service
public class LogService {
    public static final int KEEP = 1000;
    /** Mọi sự kiện nhật ký dùng chung khoá này → cùng một partition Kafka → Telegram và giao diện nhận đúng thứ tự ghi */
    static final String EVENT_KEY = "logs";

    private final LogRepository repo;
    private final EventBus events;
    /** Số dòng mới của từng workspace kể từ lần dọn gần nhất */
    private final Map<Long, AtomicInteger> sinceTrim = new ConcurrentHashMap<>();

    public LogService(LogRepository repo, EventBus events) {
        this.repo = repo;
        this.events = events;
    }

    /** Tạo dòng nhật ký mới, không báo Telegram (thao tác tay, lỗi hệ thống…). */
    public LogEntry log(Consumer<LogEntry> fill) { return log(fill, false); }

    /**
     * Tạo dòng nhật ký mới: fill điền các trường. Trả về dòng đã lưu (có id, ts).
     * Phát sự kiện log.created: giao diện, thống kê và (nếu notify) Telegram nhận.
     */
    public LogEntry log(Consumer<LogEntry> fill, boolean notify) {
        LogEntry e = new LogEntry();
        fill.accept(e);
        e.setId(Ids.uid());
        e.setTs(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        LogEntry saved = repo.save(e);
        events.publish(AppEvent.LOG_CREATED, EVENT_KEY, notify, saved);
        if (sinceTrim.computeIfAbsent(WorkspaceContext.require(), k -> new AtomicInteger()).incrementAndGet() >= 50) trim();
        return saved;
    }

    /** Giữ {@value #KEEP} dòng mới nhất của workspace hiện tại */
    public void trim() {
        long ws = WorkspaceContext.require();
        sinceTrim.remove(ws);
        Long cut = repo.seqAtOffset(ws, KEEP);
        if (cut != null) repo.deleteUpTo(ws, cut);
    }

    public List<LogEntry> recent(int n) { return repo.findAllByOrderBySeqDesc(Limit.of(n)); }

    public List<LogEntry> ofKind(String kind) { return repo.findByKindOrderBySeqDesc(kind); }

    public Optional<LogEntry> find(String id) { return repo.findById(id); }

    /** Sửa dòng đã có (vd. đánh dấu đã hoàn tác): giao diện nhận bản mới qua WebSocket, thay theo id */
    public LogEntry save(LogEntry e) {
        LogEntry saved = repo.save(e);
        events.publish(AppEvent.LOG_UPDATED, EVENT_KEY, false, saved);
        return saved;
    }
}
