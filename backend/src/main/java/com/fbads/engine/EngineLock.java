package com.fbads.engine;

import com.fbads.common.ApiException;
import com.fbads.security.WorkspaceContext;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Khoá (lưu ở Redis qua ShedLock) để không bao giờ có 2 lượt tự động hoá chạy cùng lúc trên cùng một workspace,
 * kể cả khi chạy nhiều bản tool:
 *  - NAME: cả vòng tự động, giữ bằng @SchedulerLock trên EngineTicker.tick() (mỗi lượt chỉ 1 bản tool chạy);
 *  - NAME-ws-{id}: một workspace. Vòng tự động giữ khoá này khi chạy workspace đó, nút Chạy ngay (lịch, rule) cũng xin khoá này,
 *    nên bấm Chạy ngay ở workspace A không phải chờ vòng tự động đang chạy workspace B.
 */
@Component
public class EngineLock {
    public static final String NAME = "fbads-engine";
    /** Bản tool đang giữ khoá mà chết giữa chừng thì sau thời gian này khoá tự nhả */
    public static final String AT_MOST = "PT15M";
    static final Duration WAIT = Duration.ofMinutes(2);

    private final LockProvider provider;

    public EngineLock(LockProvider provider) { this.provider = provider; }

    /** Tên khoá của một workspace */
    public static String nameOf(long workspaceId) { return NAME + "-ws-" + workspaceId; }

    private Optional<SimpleLock> tryLock(String name) {
        return provider.lock(new LockConfiguration(Instant.now(), name, Duration.parse(AT_MOST), Duration.ZERO));
    }

    /** Chạy trong khoá của workspace hiện tại: chờ lượt đang chạy ở workspace này xong (tối đa 2 phút) rồi chạy */
    public <T> T run(Supplier<T> s) {
        String name = nameOf(WorkspaceContext.require());
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        Optional<SimpleLock> lock = tryLock(name);
        while (lock.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            lock = tryLock(name);
        }
        if (lock.isEmpty()) throw new ApiException(409, "Tool đang chạy một lượt tự động khác, hãy thử lại sau ít phút.");
        try {
            return s.get();
        } finally {
            lock.get().unlock();
        }
    }
}
