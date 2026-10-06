package com.fbads.engine;

import com.fbads.client.FbException;
import com.fbads.repository.WorkspaceRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EngineState;
import com.fbads.event.AppEvent;
import com.fbads.event.EventBus;
import com.fbads.service.EventStatsService;
import com.fbads.service.LogService;
import com.fbads.service.ReportService;
import com.fbads.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * Vòng lặp của engine: 30 giây một lần (@Scheduled, fixedDelay = đợi lượt trước xong rồi mới tính giờ lượt sau).
 * Mỗi lượt chạy lần lượt từng workspace, trong WorkspaceContext của workspace đó: cài đặt, lịch, rule, token Facebook đều là của workspace ấy.
 * Mỗi phần chạy trong try/catch riêng: lịch lỗi không làm mất lượt kiểm tra rule và báo cáo (bản Node thì mất cả lượt).
 * @SchedulerLock (ShedLock, khoá ở Redis): chạy nhiều bản tool thì mỗi lượt chỉ 1 bản chạy; lượt nào không lấy được khoá thì bỏ qua.
 * Tắt bằng ENGINE_ENABLED=false (vd khi kiểm thử).
 */
@Component
@ConditionalOnProperty(name = "fbads.engine.enabled", havingValue = "true", matchIfMissing = true)
public class EngineTicker {
    private static final Logger log = LoggerFactory.getLogger(EngineTicker.class);
    /** Khoá Redis "đã kiểm tra rule trong chu kỳ này" của từng workspace: fbads:engine:rules-ran:{id} */
    static final String RULES_RAN = "fbads:engine:rules-ran:";

    private final ScheduleRunner schedules;
    private final RuleRunner rules;
    private final ReportService report;
    private final SettingsService settings;
    private final LogService logs;
    private final EngineState state;
    private final EngineClock clock;
    private final StringRedisTemplate redis;
    private final EventBus events;
    private final EventStatsService stats;
    private final WorkspaceRepository workspaces;
    private final EngineLock lock;
    private final EngineWatch watch;

    public EngineTicker(WorkspaceRepository workspaces, EngineLock lock, StringRedisTemplate redis, ScheduleRunner schedules, RuleRunner rules, ReportService report, SettingsService settings,
                        LogService logs, EngineState state, EngineClock clock, EventBus events,
                        EventStatsService stats, EngineWatch watch) {
        this.watch = watch;
        this.workspaces = workspaces;
        this.lock = lock;
        this.events = events;
        this.stats = stats;
        this.redis = redis;
        this.schedules = schedules;
        this.rules = rules;
        this.report = report;
        this.settings = settings;
        this.logs = logs;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 3000, fixedDelayString = "${fbads.engine.tick-ms:30000}")
    @SchedulerLock(name = EngineLock.NAME, lockAtMostFor = EngineLock.AT_MOST)
    public void tick() {
        runOnce();
    }

    /** Một lượt: lần lượt từng workspace, mỗi workspace chạy riêng (workspace này lỗi token không làm hỏng workspace khác) */
    void runOnce() {
        watch.tickStarted();
        try {
            for (long ws : workspaces.allIds()) {
                try {
                    WorkspaceContext.run(ws, () -> lock.run(() -> { runWorkspace(); return null; }));
                } catch (RuntimeException e) {
                    log.error("Lỗi tick (workspace {}): {}", ws, e.getMessage(), e);
                }
            }
        } finally {
            watch.tickDone();
        }
    }

    private void runWorkspace() {
        watch.workspaceStarted();
        RuntimeException first = step("lịch", schedules::tick);
        first = firstOf(first, step("bật lại theo hẹn", rules::tickResumes));
        long every = Math.max(1, settings.get().getRuleIntervalMin()) * 60_000L;
        // "Đến giờ kiểm tra rule chưa" lưu ở Redis (SET NX + hết hạn) để nhiều bản tool không cùng chạy rule trong một chu kỳ
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(RULES_RAN + WorkspaceContext.require(), "1", Duration.ofMillis(every))))
            first = firstOf(first, step("rule", rules::runRules));
        first = firstOf(first, step("báo cáo", report::tick));
        step("dọn dẹp", () -> state.cleanupDaily(LocalDate.parse(clock.now().date()).minusDays(7).toString()));
        // kiểm tra token: lỗi ở đây không tính là lượt lỗi
        try { watch.tickToken(); } catch (RuntimeException e) { log.error("Lỗi kiểm tra token: {}", e.getMessage()); }
        try { watch.workspaceDone(first); } catch (RuntimeException e) { log.error("Lỗi báo trạng thái vòng tự động: {}", e.getMessage()); }
        events.publish(AppEvent.ENGINE_TICK, "engine", false, Map.of("at", clock.millis()));
    }

    private static RuntimeException firstOf(RuntimeException a, RuntimeException b) { return a != null ? a : b; }

    /** Dọn các dấu "sự kiện đã đếm" (chung mọi workspace), mỗi giờ một lần là đủ */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    @SchedulerLock(name = "fbads-stats-cleanup", lockAtMostFor = "10m")
    public void cleanupStats() {
        try {
            stats.cleanup(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(7).toString());
        } catch (RuntimeException e) {
            log.warn("Không dọn được event_stats_seen: {}", e.getMessage());
        }
    }

    /** Chạy một phần của lượt; lỗi thì ghi nhật ký và trả về lỗi (null = ổn) */
    private RuntimeException step(String name, Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            log.error("Lỗi tick ({}): {}", name, e.getMessage(), e);
            String mode = settings.get().mode();
            try {
                logs.log(l -> {
                    l.setKind("system"); l.setSource("Hệ thống"); l.setName("-"); l.setDetail(e.getMessage()); l.setOk(false); l.setMode(mode);
                    l.setError(FbException.describe(e));
                });
            } catch (RuntimeException ignored) { /* DB lỗi: đã ghi log ra console */ }
            return e;
        }
    }
}
