package com.fbads.engine;

import com.fbads.client.FbException;
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
 * Mỗi phần chạy trong try/catch riêng: lịch lỗi không làm mất lượt kiểm tra rule và báo cáo (bản Node thì mất cả lượt).
 * @SchedulerLock (ShedLock, khoá ở Redis): chạy nhiều bản tool thì mỗi lượt chỉ 1 bản chạy; lượt nào không lấy được khoá thì bỏ qua.
 * Tắt bằng ENGINE_ENABLED=false (vd khi kiểm thử).
 */
@Component
@ConditionalOnProperty(name = "fbads.engine.enabled", havingValue = "true", matchIfMissing = true)
public class EngineTicker {
    private static final Logger log = LoggerFactory.getLogger(EngineTicker.class);

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

    public EngineTicker(StringRedisTemplate redis, ScheduleRunner schedules, RuleRunner rules, ReportService report, SettingsService settings,
                        LogService logs, EngineState state, EngineClock clock, EventBus events,
                        EventStatsService stats) {
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

    void runOnce() {
        step("lịch", schedules::tick);
        step("bật lại theo hẹn", rules::tickResumes);
        long every = Math.max(1, settings.get().getRuleIntervalMin()) * 60_000L;
        // "Đến giờ kiểm tra rule chưa" lưu ở Redis (SET NX + hết hạn) để nhiều bản tool không cùng chạy rule trong một chu kỳ
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("fbads:engine:rules-ran", "1", Duration.ofMillis(every))))
            step("rule", rules::runRules);
        step("báo cáo", report::tick);
        step("dọn dẹp", () -> {
            String weekAgo = LocalDate.parse(clock.now().date()).minusDays(7).toString();
            state.cleanupDaily(weekAgo);
            stats.cleanup(weekAgo);
        });
        events.publish(AppEvent.ENGINE_TICK, "engine", false, Map.of("at", clock.millis()));
    }

    private void step(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.error("Lỗi tick ({}): {}", name, e.getMessage(), e);
            String mode = settings.get().mode();
            try {
                logs.log(l -> {
                    l.setKind("system"); l.setSource("Hệ thống"); l.setName("-"); l.setDetail(e.getMessage()); l.setOk(false); l.setMode(mode);
                    l.setError(FbException.describe(e));
                });
            } catch (RuntimeException ignored) { /* DB lỗi: đã ghi log ra console */ }
        }
    }
}
