package com.fbads.engine.state;

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
 */
@Entity
@Table(name = "daily_marks")
public class DailyMark {
    @Embeddable
    public record Key(@Column(name = "day") String day, @Column(name = "mark") String mark) {}

    @EmbeddedId
    private Key key;
    private Double numValue;

    protected DailyMark() {}

    public DailyMark(String day, String mark, Double value) {
        this.key = new Key(day, mark);
        this.numValue = value;
    }

    public Key getKey() { return key; }
    public Double getNumValue() { return numValue; }
}
