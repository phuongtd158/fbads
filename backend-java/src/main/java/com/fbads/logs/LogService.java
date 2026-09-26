package com.fbads.logs;

import com.fbads.common.Ids;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Ghi nhật ký và giữ lại 1000 dòng mới nhất (như bản Node). */
@Service
public class LogService {
    public static final int KEEP = 1000;

    private final LogRepository repo;
    private final AtomicInteger sinceTrim = new AtomicInteger();

    public LogService(LogRepository repo) { this.repo = repo; }

    /** Tạo dòng nhật ký mới: fill điền các trường. Trả về dòng đã lưu (có id, ts). */
    public LogEntry log(Consumer<LogEntry> fill) {
        LogEntry e = new LogEntry();
        fill.accept(e);
        e.setId(Ids.uid());
        e.setTs(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        LogEntry saved = repo.save(e);
        if (sinceTrim.incrementAndGet() >= 50) trim();
        return saved;
    }

    public void trim() {
        sinceTrim.set(0);
        Long cut = repo.seqAtOffset(KEEP);
        if (cut != null) repo.deleteUpTo(cut);
    }

    public List<LogEntry> recent(int n) { return repo.findAllByOrderBySeqDesc(Limit.of(n)); }

    public List<LogEntry> ofKind(String kind) { return repo.findByKindOrderBySeqDesc(kind); }

    public Optional<LogEntry> find(String id) { return repo.findById(id); }

    public LogEntry save(LogEntry e) { return repo.save(e); }
}
