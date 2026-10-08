package com.fbads.settings;

import com.fbads.notify.NotifyTarget;
import com.fbads.notify.NotifyTargetRepository;
import com.fbads.security.WorkspaceContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Đọc/ghi cài đặt của workspace hiện tại (WorkspaceContext). Engine đọc cài đặt rất nhiều lần mỗi lượt nên giữ
 * một bản trong bộ nhớ cho mỗi workspace; mỗi lần lưu thì ghi DB rồi thay bản trong bộ nhớ.
 */
@Service
public class SettingsService {
    private final SettingsRepository repo;
    private final NotifyTargetRepository notifyTargets;
    private final JsonMapper mapper;
    private final Map<Long, AppSettings> current = new ConcurrentHashMap<>();
    /** Khoá theo workspace: 2 lần lưu cùng lúc của một workspace đi lần lượt, workspace khác không phải chờ */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SettingsService(SettingsRepository repo, NotifyTargetRepository notifyTargets, JsonMapper mapper) {
        this.repo = repo;
        this.notifyTargets = notifyTargets;
        this.mapper = mapper;
    }

    public AppSettings get() {
        long ws = WorkspaceContext.require();
        AppSettings s = current.get(ws);
        if (s != null) return s;
        ReentrantLock lock = lockOf(ws);
        lock.lock();
        try {
            return current.computeIfAbsent(ws, id -> repo.findById(id).orElseGet(() -> repo.save(new AppSettings(id))));
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockOf(long ws) { return locks.computeIfAbsent(ws, k -> new ReentrantLock()); }

    /** Sửa rồi lưu ngay. Trả về bản đã lưu. */
    public AppSettings update(Consumer<AppSettings> change) {
        long ws = WorkspaceContext.require();
        ReentrantLock lock = lockOf(ws);
        lock.lock();
        try {
            AppSettings s = get();
            change.accept(s);
            AppSettings saved = repo.save(s);
            current.put(ws, saved);
            return saved;
        } finally {
            lock.unlock();
        }
    }

    /** Ghi các khoá đã qua kiểm tra (map khoá JSON → giá trị) vào cài đặt. */
    public AppSettings apply(Map<String, Object> values) {
        return update(s -> mapper.updateValue(s, values));
    }

    /** Đọc lại từ DB (vd sau khi nhập dữ liệu cũ, hoặc khi test đổi DB) */
    public void reload() { current.clear(); }

    /** Cài đặt gửi về giao diện: bí mật để trống + cờ has_…, has_notify = có kênh thông báo nào đang bật */
    public PublicSettings publicSettings() {
        return PublicSettings.of(get(), notifyTargets.findAll().stream().anyMatch(NotifyTarget::isEnabled));
    }
}
