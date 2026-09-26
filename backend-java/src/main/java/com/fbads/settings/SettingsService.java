package com.fbads.settings;

import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Đọc/ghi cài đặt. Engine đọc cài đặt rất nhiều lần mỗi lượt nên giữ một bản trong bộ nhớ,
 * mỗi lần lưu thì ghi DB rồi thay bản trong bộ nhớ (1 bản app; chạy nhiều bản thì giai đoạn 2 dùng Redis).
 */
@Service
public class SettingsService {
    /** Bí mật: không bao giờ gửi về giao diện, chỉ cho biết đã có hay chưa (has_…) */
    public static final List<String> SECRETS = List.of("accessToken", "telegramToken", "passwordHash", "fbAppSecret");

    private final SettingsRepository repo;
    private final JsonMapper mapper;
    private volatile AppSettings current;

    public SettingsService(SettingsRepository repo, JsonMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public AppSettings get() {
        AppSettings s = current;
        if (s == null) {
            synchronized (this) {
                if (current == null) current = repo.findById(1).orElseGet(() -> repo.save(new AppSettings()));
                s = current;
            }
        }
        return s;
    }

    /** Sửa rồi lưu ngay. Trả về bản đã lưu. */
    public synchronized AppSettings update(Consumer<AppSettings> change) {
        AppSettings s = get();
        change.accept(s);
        current = repo.save(s);
        return current;
    }

    /** Ghi các khoá đã qua kiểm tra (map khoá JSON → giá trị) vào cài đặt. Không bao giờ ghi passwordHash từ đây. */
    public AppSettings apply(Map<String, Object> values) {
        return update(s -> {
            Map<String, Object> safe = new java.util.LinkedHashMap<>(values);
            safe.remove("passwordHash");
            mapper.updateValue(s, safe);
        });
    }

    /** Đọc lại từ DB (vd sau khi nhập dữ liệu cũ, hoặc khi test đổi DB) */
    public synchronized void reload() { current = null; }

    /** Cài đặt gửi về giao diện: bí mật để trống + cờ has_… */
    public ObjectNode publicSettings() {
        ObjectNode out = mapper.valueToTree(get());
        for (String k : SECRETS) {
            String v = out.path(k).asString("");
            out.put("has_" + k, !v.isEmpty());
            out.put(k, "");
        }
        return out;
    }
}
