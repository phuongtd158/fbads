package com.fbads.engine;

import com.fbads.common.ApiException;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Khoá dùng chung (lưu ở Redis qua ShedLock) cho vòng tự động và các nút "Chạy ngay" (lịch, rule):
 * không bao giờ có 2 lượt tự động hoá chạy cùng lúc trên cùng một camp, kể cả khi chạy nhiều bản tool.
 * Vòng tự động giữ khoá bằng @SchedulerLock(name = NAME) trên EngineTicker.tick(); nút Chạy ngay xin cùng khoá đó ở đây.
 */
@Component
public class EngineLock {
    public static final String NAME = "fbads-engine";
    /** Bản tool đang giữ khoá mà chết giữa chừng thì sau thời gian này khoá tự nhả */
    public static final String AT_MOST = "PT15M";
    static final Duration WAIT = Duration.ofMinutes(2);

    private final LockProvider provider;

    public EngineLock(LockProvider provider) { this.provider = provider; }

    private Optional<SimpleLock> tryLock() {
        return provider.lock(new LockConfiguration(Instant.now(), NAME, Duration.parse(AT_MOST), Duration.ZERO));
    }

    /** Cho nút Chạy ngay: chờ lượt đang chạy xong (tối đa 2 phút) rồi chạy */
    public <T> T run(Supplier<T> s) {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        Optional<SimpleLock> lock = tryLock();
        while (lock.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            lock = tryLock();
        }
        if (lock.isEmpty()) throw new ApiException(409, "Tool đang chạy một lượt tự động khác, hãy thử lại sau ít phút.");
        try {
            return s.get();
        } finally {
            lock.get().unlock();
        }
    }
}
