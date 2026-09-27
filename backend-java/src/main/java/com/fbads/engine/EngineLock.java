package com.fbads.engine;

import com.fbads.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Khoá dùng chung cho vòng tick và các nút "Chạy ngay" (lịch, rule): không bao giờ có 2 lượt tự động hoá chạy cùng lúc
 * trên cùng một camp (bản Node thiếu chỗ này: nút Chạy ngay có thể chạy chồng lên tick).
 */
@Component
public class EngineLock {
    private final ReentrantLock lock = new ReentrantLock();

    /** Cho tick: đang có lượt khác chạy thì bỏ qua lượt này */
    public boolean tryRun(Runnable r) {
        if (!lock.tryLock()) return false;
        try { r.run(); return true; } finally { lock.unlock(); }
    }

    /** Cho nút Chạy ngay: chờ lượt đang chạy xong (tối đa 2 phút) rồi chạy */
    public <T> T run(Supplier<T> s) {
        boolean got;
        try {
            got = lock.tryLock(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = false;
        }
        if (!got) throw new ApiException(409, "Tool đang chạy một lượt tự động khác, hãy thử lại sau ít phút.");
        try { return s.get(); } finally { lock.unlock(); }
    }
}
