package com.fbads.service;

import com.fbads.security.WorkspaceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Trạng thái nhỏ của workspace hiện tại, lưu dạng khoá → JSON trong bảng workspace_state (thay cho data.state của bản Node):
 * khởi động lại server không mất, chạy nhiều bản tool thì dùng chung.
 * Dùng cho những thứ chỉ cần nhớ vài giá trị: đã báo token sắp hết hạn chưa, quảng cáo bị từ chối đã báo, báo cáo tuần đã gửi…
 */
@Service
public class WsState {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public WsState(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Giá trị của khoá, null nếu chưa có (hoặc không đọc được thành kiểu này) */
    public <T> T get(String key, Class<T> type) {
        List<String> rows = jdbc.queryForList("SELECT v FROM workspace_state WHERE workspace_id = ? AND k = ?", String.class,
                WorkspaceContext.require(), key);
        if (rows.isEmpty()) return null;
        try {
            return mapper.readValue(rows.getFirst(), type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public <T> T get(String key, Class<T> type, T fallback) {
        T v = get(key, type);
        return v != null ? v : fallback;
    }

    public void put(String key, Object value) {
        jdbc.update("INSERT INTO workspace_state (workspace_id, k, v, updated_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY "
                + "UPDATE v = VALUES(v), updated_at = VALUES(updated_at)",
                WorkspaceContext.require(), key, mapper.writeValueAsString(value), Timestamp.from(Instant.now()));
    }

    public void remove(String key) {
        jdbc.update("DELETE FROM workspace_state WHERE workspace_id = ? AND k = ?", WorkspaceContext.require(), key);
    }

    /** Xoá trạng thái của mọi workspace (dùng cho kiểm thử) */
    public void clearAll() { jdbc.update("DELETE FROM workspace_state"); }
}
