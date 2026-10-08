package com.fbads.ads;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Một chiến dịch (level = campaign) hoặc nhóm quảng cáo (level = adset) đọc từ Facebook (hoặc dữ liệu giả).
 * Không lưu DB: luôn lấy mới từ Facebook, có bộ nhớ đệm ngắn trong service.facebook.FacebookState.
 * <p>
 * Tạo bằng {@link #builder}. Sau khi tạo chỉ đổi được trạng thái và ngân sách, qua {@link #applyStatus} và
 * {@link #applyBudget}: engine gọi ngay sau khi thao tác để các rule sau trong cùng lượt thấy trạng thái mới.
 * JSON (gửi giao diện, lưu Redis) đọc/ghi thẳng các trường.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class AdObject {
    private String id;
    private String name;
    private AdLevel level;
    private String campaignId;              // chỉ nhóm QC
    private volatile String status;         // ACTIVE | PAUSED | …  (trạng thái bật/tắt do người dùng đặt)
    private volatile String effective;      // effective_status: trạng thái thực tế Facebook báo
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private volatile Double dailyBudget;    // null = không có ngân sách riêng ở cấp này (CBO/ABO)
    private boolean learning;               // đang trong giai đoạn học
    private Long startTime;                 // nhóm QC: mili giây
    private Long endTime;
    private Metrics metrics = Metrics.EMPTY;
    private String accountId;
    private String accountName;
    private String currency;
    @JsonIgnore
    private int seed;                       // chỉ dùng cho dữ liệu giả

    private AdObject() {} // cho Jackson

    /** Bắt đầu tạo một camp/nhóm QC: id, tên, cấp; các phần khác thêm bằng các hàm của Builder */
    public static Builder builder(String id, String name, AdLevel level) {
        AdObject o = new AdObject();
        o.id = id;
        o.name = name;
        o.level = level;
        return new Builder(o);
    }

    /** Các bước tạo AdObject; build() trả về đối tượng đã xong */
    public static final class Builder {
        private final AdObject o;

        private Builder(AdObject o) { this.o = o; }

        /** status = do người dùng đặt, effective = Facebook báo thực tế */
        public Builder state(String status, String effective) { o.status = status; o.effective = effective; return this; }

        public Builder budget(Double dailyBudget) { o.dailyBudget = dailyBudget; return this; }

        public Builder learning(boolean learning) { o.learning = learning; return this; }

        /** Nhóm QC: thuộc chiến dịch nào, chạy từ/đến lúc nào (ms) */
        public Builder adset(String campaignId, Long startTime, Long endTime) {
            o.campaignId = campaignId; o.startTime = startTime; o.endTime = endTime; return this;
        }

        public Builder metrics(Metrics metrics) { o.metrics = metrics; return this; }

        public Builder account(String accountId, String accountName, String currency) {
            o.accountId = accountId; o.accountName = accountName; o.currency = currency; return this;
        }

        public Builder seed(int seed) { o.seed = seed; return this; }

        public AdObject build() { return o; }
    }

    /** Bản sao (dữ liệu giả: mỗi lần lấy danh sách là một bản riêng) */
    public AdObject copy() {
        AdObject o = new AdObject();
        o.id = id; o.name = name; o.level = level; o.campaignId = campaignId; o.status = status; o.effective = effective;
        o.dailyBudget = dailyBudget; o.learning = learning; o.startTime = startTime; o.endTime = endTime; o.metrics = metrics;
        o.accountId = accountId; o.accountName = accountName; o.currency = currency; o.seed = seed;
        return o;
    }

    /** Bản sao với số liệu khác */
    public AdObject withMetrics(Metrics m) {
        AdObject o = copy();
        o.metrics = m;
        return o;
    }

    /** Vừa bật/tắt xong: cả trạng thái đặt lẫn trạng thái thực tế đổi theo */
    public void applyStatus(boolean active) { status = effective = active ? "ACTIVE" : "PAUSED"; }

    /** Vừa đổi ngân sách xong */
    public void applyBudget(double dailyBudget) { this.dailyBudget = dailyBudget; }

    public String id() { return id; }

    public String name() { return name; }

    public AdLevel level() { return level; }

    public String campaignId() { return campaignId; }

    public String status() { return status; }

    public String effective() { return effective; }

    public Double dailyBudget() { return dailyBudget; }

    public boolean learning() { return learning; }

    public Long startTime() { return startTime; }

    public Long endTime() { return endTime; }

    public Metrics metrics() { return metrics; }

    public String accountId() { return accountId; }

    public String accountName() { return accountName; }

    public String currency() { return currency; }

    public int seed() { return seed; }

    public boolean isCampaign() { return level == AdLevel.CAMPAIGN; }

    public boolean isActive() { return "ACTIVE".equals(effective); }

    /** Đã lưu trữ hoặc đã xoá trên Facebook */
    public boolean isRemoved() { return "ARCHIVED".equals(effective) || "DELETED".equals(effective); }

    /**
     * Vì sao không đổi ngân sách được ở cấp này. Ngân sách chỉ nằm ở 1 cấp: camp CBO giữ ngân sách (nhóm QC không có),
     * camp ABO thì ngược lại.
     */
    public String noBudgetReason() {
        return level == AdLevel.ADSET
                ? "Nhóm QC không có ngân sách riêng (chiến dịch dùng ngân sách chiến dịch - CBO), chỉnh ngân sách ở cấp chiến dịch"
                : "Chiến dịch không có ngân sách riêng (ngân sách đặt ở từng nhóm QC - ABO), hãy dùng rule cấp nhóm QC";
    }

    /** "camp" hoặc "nhóm QC" để ghép câu */
    public String unit() { return level == AdLevel.ADSET ? "nhóm QC" : "camp"; }
}
