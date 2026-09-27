package com.fbads.security;

import java.util.function.Supplier;

/**
 * Workspace đang làm việc của luồng hiện tại. Mọi chỗ đọc dữ liệu (SettingsService, repository qua @TenantId, cache Facebook…)
 * dựa vào đây để chỉ thấy dữ liệu của workspace này.
 * Nơi đặt: WorkspaceFilter (mỗi request), EngineTicker (từng workspace một), consumer sự kiện (workspace ghi trong sự kiện),
 * callback đăng nhập Facebook, bước khởi động.
 * InheritableThreadLocal: luồng con (virtual thread tải song song nhiều trang Facebook) nhận luôn workspace của luồng tạo ra nó.
 */
public final class WorkspaceContext {
    /** Workspace có sẵn từ đầu: dữ liệu của bản 1 người dùng nằm ở đây */
    public static final long DEFAULT = 1L;

    private static final InheritableThreadLocal<Long> CURRENT = new InheritableThreadLocal<>();

    private WorkspaceContext() {}

    /** null = luồng này chưa gắn workspace */
    public static Long current() { return CURRENT.get(); }

    /** Workspace hiện tại; chưa gắn thì báo lỗi ngay (không bao giờ đoán, để không đọc/ghi nhầm dữ liệu của người khác) */
    public static long require() {
        Long id = CURRENT.get();
        if (id == null) throw new IllegalStateException("Chưa chọn workspace cho thao tác này");
        return id;
    }

    /** Gắn workspace cho luồng tới khi close(): try (var s = WorkspaceContext.enter(id)) { … }. close() trả lại workspace cũ. */
    public static Scope enter(long id) {
        Long prev = CURRENT.get();
        CURRENT.set(id);
        return () -> {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Chạy fn trong workspace id, xong thì trả lại workspace cũ của luồng */
    public static <T> T call(long id, Supplier<T> fn) {
        try (Scope s = enter(id)) {
            return fn.get();
        }
    }

    public static void run(long id, Runnable fn) {
        call(id, () -> { fn.run(); return null; });
    }
}
