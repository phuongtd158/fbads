package com.fbads.notify;

import com.fbads.common.JsonConverters;
import com.fbads.common.SecretConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.util.ArrayList;
import java.util.List;

/**
 * Một kênh thông báo workspace đã cài (bảng notify_targets): "nhóm Telegram team A", "email sếp"…
 * Loại kênh (type) khớp với một class NotifyChannel trong gói notify/channel, class đó biết cách gửi.
 * Không trả thẳng entity này về giao diện vì config chứa bí mật: xem NotifyTargetService.view().
 */
@Entity
@Table(name = "notify_targets")
public class NotifyTarget {
    @Id
    private String id;
    /** Workspace chủ của dòng này: Hibernate tự ghi khi tạo và tự lọc khi đọc (config/TenantConfig) */
    @TenantId
    @Column(name = "workspace_id", updatable = false)
    private Long workspaceId;
    @Column(insertable = false, updatable = false)
    private Long seq;
    private String type;
    private String name = "";
    private boolean enabled = true;
    /** Tên các Notice.Topic kênh này nhận */
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> topics = new ArrayList<>();
    /** Cấu hình của kênh dạng chuỗi JSON (token, người nhận…); mã hoá cả chuỗi trong DB khi có SECRET_KEY */
    @Convert(converter = SecretConverter.class)
    private String config = "{}";

    protected NotifyTarget() {}

    public NotifyTarget(String id, String type) {
        this.id = id;
        this.type = type;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
}
