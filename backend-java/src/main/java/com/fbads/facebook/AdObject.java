package com.fbads.facebook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Một chiến dịch (level = campaign) hoặc nhóm quảng cáo (level = adset) đọc từ Facebook (hoặc dữ liệu giả).
 * Không lưu DB: luôn lấy mới từ Facebook, có bộ nhớ đệm ngắn trong FacebookService.
 * status/effective/dailyBudget đổi được: engine cập nhật ngay sau khi thao tác để các rule sau trong cùng lượt thấy trạng thái mới.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdObject {
    public String id;
    public String name;
    public String level;          // campaign | adset
    public String campaignId;     // chỉ nhóm QC
    public volatile String status;         // ACTIVE | PAUSED | …  (trạng thái bật/tắt do người dùng đặt)
    public volatile String effective;      // effective_status: trạng thái thực tế Facebook báo
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public volatile Double dailyBudget;    // null = không có ngân sách riêng ở cấp này (CBO/ABO)
    public boolean learning;      // đang trong giai đoạn học
    public Long startTime;        // nhóm QC: mili giây
    public Long endTime;
    public Metrics metrics = Metrics.EMPTY;
    public String accountId;
    public String accountName;
    public String currency;
    @JsonIgnore
    public int seed;              // chỉ dùng cho dữ liệu giả

    public AdObject copy() {
        AdObject o = new AdObject();
        o.id = id; o.name = name; o.level = level; o.campaignId = campaignId; o.status = status; o.effective = effective;
        o.dailyBudget = dailyBudget; o.learning = learning; o.startTime = startTime; o.endTime = endTime; o.metrics = metrics;
        o.accountId = accountId; o.accountName = accountName; o.currency = currency; o.seed = seed;
        return o;
    }

    @JsonIgnore
    public boolean isCampaign() { return "campaign".equals(level); }

    @JsonIgnore
    public boolean isActive() { return "ACTIVE".equals(effective); }

    /** "camp" hoặc "nhóm QC" để ghép câu */
    @JsonIgnore
    public String unit() { return "adset".equals(level) ? "nhóm QC" : "camp"; }
}
