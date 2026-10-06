package com.fbads.security;

import java.util.function.Supplier;

/**
 * Workspace đang làm việc của luồng hiện tại, giống tờ giấy nhớ "tôi đang làm cho workspace số mấy" dán trên luồng.
 * Mọi chỗ đọc dữ liệu (SettingsService, repository qua @TenantId, cache Facebook…) nhìn tờ giấy này để chỉ thấy dữ liệu
 * của workspace đó.
 * <p>
 * Ai dán giấy: WorkspaceFilter (mỗi request), EngineTicker (từng workspace một), consumer sự kiện (workspace ghi trong
 * sự kiện), callback đăng nhập Facebook, bước khởi động. Code chính chỉ dùng run() / call(); enter() dành cho test.
 */
public final class WorkspaceContext {
    /** Workspace có sẵn từ đầu: dữ liệu của bản 1 người dùng nằm ở đây */
    public static final long DEFAULT = 1L;

    /**
     * Nơi giữ tờ giấy nhớ. ThreadLocal: mỗi luồng có một giá trị riêng, hai request chạy cùng lúc không thấy của nhau.
     * Inheritable: luồng con (virtual thread tải song song nhiều trang Facebook) nhận luôn workspace của luồng tạo ra nó.
     */
    private static final InheritableThreadLocal<Long> CURRENT = new InheritableThreadLocal<>();

    private WorkspaceContext() {}

    /** Workspace hiện tại, null = luồng này chưa gắn workspace */
    public static Long current() {
        return CURRENT.get();
    }

    /** Workspace hiện tại; chưa gắn thì báo lỗi ngay (không bao giờ đoán, để không đọc/ghi nhầm dữ liệu của người khác) */
    public static long require() {
        Long id = CURRENT.get();
        if (id == null) throw new IllegalStateException("Chưa chọn workspace cho thao tác này");
        return id;
    }

    /**
     * Chạy fn trong workspace id rồi trả kết quả của fn. Xong việc (kể cả khi fn ném lỗi) luồng quay về workspace cũ.
     * Ví dụ: {@code int n = WorkspaceContext.call(2, () -> rules.count());}
     */
    public static <T> T call(long id, Supplier<T> fn) {
        Long before = CURRENT.get(); // 1. nhớ workspace cũ của luồng (có thể là null)
        CURRENT.set(id);             // 2. gắn workspace mới
        try {
            return fn.get();         // 3. làm việc: mọi truy vấn trong fn thấy workspace id
        } finally {
            restore(before);         // 4. finally luôn chạy, kể cả khi bước 3 lỗi: trả lại workspace cũ
        }
    }

    /** Giống call() nhưng cho việc không cần trả kết quả. Ví dụ: {@code WorkspaceContext.run(ws, () -> runWorkspace());} */
    public static void run(long id, Runnable fn) {
        call(id, () -> {
            fn.run();
            return null; // call() cần một giá trị trả về, việc này không có nên trả null
        });
    }

    /**
     * Đưa luồng về workspace trước đó.
     * Trả về cái cũ chứ không xoá thẳng, để lồng nhau được: đang ở 1, call(2, …) xong thì vẫn đúng là 1.
     * Trước đó chưa có gì thì xoá hẳn: Tomcat dùng lại luồng cho request sau, không được để sót workspace của người trước.
     */
    private static void restore(Long before) {
        if (before == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(before);
        }
    }

    /**
     * Gắn workspace cho luồng tới khi gọi close() trên kết quả trả về. Dành cho test: gắn ở @BeforeEach, close() ở
     * @AfterEach, vì lúc đó không bọc được việc cần làm vào một hàm như run() / call().
     * Có thể dùng với try: {@code try (var s = WorkspaceContext.enter(1)) { … }} thì Java tự gọi close() khi ra khỏi khối.
     */
    public static Scope enter(long id) {
        Scope scope = new Scope(CURRENT.get()); // nhớ workspace cũ vào scope
        CURRENT.set(id);                        // gắn workspace mới
        return scope;                           // close() sau này sẽ trả lại workspace cũ
    }

    /**
     * "Vé" do enter() trả về: giữ workspace cũ, close() thì trả lại nó.
     * implements AutoCloseable: để dùng được trong try (…) như ví dụ ở enter().
     * close() của AutoCloseable khai báo throws Exception; ở đây không ném gì nên bỏ throws đi, chỗ gọi không cần catch.
     */
    public static final class Scope implements AutoCloseable {
        private final Long before;

        private Scope(Long before) {
            this.before = before;
        }

        @Override
        public void close() {
            restore(before);
        }
    }
}
