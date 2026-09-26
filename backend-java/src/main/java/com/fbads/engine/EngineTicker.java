package com.fbads.engine;

import com.fbads.engine.state.EngineState;
import com.fbads.facebook.FbException;
import com.fbads.logs.LogService;
import com.fbads.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Vòng lặp của engine: 30 giây một lần (@Scheduled, fixedDelay = đợi lượt trước xong rồi mới tính giờ lượt sau).
 * Mỗi phần chạy trong try/catch riêng: lịch lỗi không làm mất lượt kiểm tra rule và báo cáo (bản Node thì mất cả lượt).
 * Tắt bằng ENGINE_ENABLED=false (vd khi kiểm thử).
 */
@Component
@ConditionalOnProperty(name = "fbads.engine.enabled", havingValue = "true", matchIfMissing = true)
public class EngineTicker {
    private static final Logger log = LoggerFactory.getLogger(EngineTicker.class);

    private final EngineLock lock;
    private final ScheduleRunner schedules;
    private final RuleRunner rules;
    private final ReportService report;
    private final SettingsService settings;
    private final LogService logs;
    private final EngineState state;
    private final EngineClock clock;
    private volatile long lastRules = 0;

    public EngineTicker(EngineLock lock, ScheduleRunner schedules, RuleRunner rules, ReportService report, SettingsService settings,
                        LogService logs, EngineState state, EngineClock clock) {
        this.lock = lock;
        this.schedules = schedules;
        this.rules = rules;
        this.report = report;
        this.settings = settings;
        this.logs = logs;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 3000, fixedDelayString = "${fbads.engine.tick-ms:30000}")
    public void tick() {
        if (!lock.tryRun(this::runOnce)) log.debug("Lượt trước vẫn đang chạy, bỏ qua lượt này.");
    }

    void runOnce() {
        step("lịch", schedules::tick);
        step("bật lại theo hẹn", rules::tickResumes);
        long every = Math.max(1, settings.get().getRuleIntervalMin()) * 60_000L;
        if (System.currentTimeMillis() - lastRules >= every) {
            lastRules = System.currentTimeMillis();
            step("rule", rules::runRules);
        }
        step("báo cáo", report::tick);
        step("dọn dẹp", () -> state.cleanupDaily(LocalDate.parse(clock.now().date()).minusDays(7).toString()));
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
