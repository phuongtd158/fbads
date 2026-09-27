package com.fbads.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Đánh dấu theo ngày (tự hết hiệu lực sang ngày mới):
 *   base:{objId}          ngân sách gốc của camp hôm nay (giới hạn tổng thay đổi/ngày của rule)
 *   skip:{ref}:{obj}:{code} lý do bỏ qua đã ghi nhật ký
 *   kill:total, kill:acc:{id} dừng khẩn đã chạy
 *   report                 báo cáo hằng ngày đã gửi
 * Khoá có workspace: mỗi workspace một bộ dấu riêng (không dùng @TenantId vì workspace nằm trong khoá chính).
 */
@Entity
@Table(name = "daily_marks")
public class DailyMark {
    @Embeddable
    public record Key(@Column(name = "workspace_id") Long workspaceId, @Column(name = "day") String day, @Column(name = "mark") String mark) {}

    @EmbeddedId
    private Key key;
    private Double numValue;

    protected DailyMark() {}

    public DailyMark(long workspaceId, String day, String mark, Double value) {
        this.key = new Key(workspaceId, day, mark);
        this.numValue = value;
    }

    public Key getKey() { return key; }
    public Double getNumValue() { return numValue; }
}
