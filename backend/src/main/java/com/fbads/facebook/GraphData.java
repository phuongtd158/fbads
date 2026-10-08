package com.fbads.facebook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Hình dạng JSON mà Graph API của Facebook trả về, khai báo thành record để Jackson đọc thẳng vào
 * (thay cho việc đi lần từng khoá kiểu {@code json.path("name").asString("")}).
 * <p>
 * Chỉ khai báo những trường tool dùng; trường khác Facebook gửi thêm thì bỏ qua. Chữ trống / thiếu thì record tự đổi
 * thành "" (giống cách bản Node đọc), riêng các trường "có thể không có" (ngân sách, giờ, token) giữ null.
 * Facebook gửi số liệu (spend, impressions…) dạng chuỗi nên giữ String, đổi sang số ở FacebookParse.
 */
public final class GraphData {
    private GraphData() {}

    private static String text(String s) { return s == null ? "" : s; }

    /** Tài khoản quảng cáo (act_…, hoặc một dòng của me/adaccounts) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(@JsonProperty("account_id") String accountId, String name, String currency,
            @JsonProperty("account_status") Integer accountStatus) {
        public Account {
            accountId = text(accountId);
            name = text(name);
            currency = text(currency);
        }

        /** Mã trạng thái (1 = đang hoạt động); thiếu → 0 */
        public int status() { return accountStatus == null ? 0 : accountStatus; }
    }

    /** Chiến dịch. dailyBudget theo đơn vị nhỏ nhất của tiền tệ (cent), null = không có ngân sách riêng */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Campaign(String id, String name, String status, @JsonProperty("effective_status") String effectiveStatus,
            @JsonProperty("daily_budget") String dailyBudget) {
        public Campaign {
            id = text(id);
            name = text(name);
            status = text(status);
            effectiveStatus = text(effectiveStatus);
        }
    }

    /** Nhóm quảng cáo */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdSet(String id, String name, String status, @JsonProperty("effective_status") String effectiveStatus,
            @JsonProperty("daily_budget") String dailyBudget, @JsonProperty("campaign_id") String campaignId,
            @JsonProperty("start_time") String startTime, @JsonProperty("end_time") String endTime,
            @JsonProperty("learning_stage_info") LearningStage learningStage) {
        public AdSet {
            id = text(id);
            name = text(name);
            status = text(status);
            effectiveStatus = text(effectiveStatus);
            campaignId = text(campaignId);
        }

        /** Đang trong giai đoạn học */
        public boolean isLearning() { return learningStage != null && "LEARNING".equals(learningStage.status()); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LearningStage(String status) {}

    /**
     * Một dòng Insights: của một camp (campaignId), một nhóm QC (adsetId), một ngày (dateStart, khi chia theo ngày)
     * hoặc một giờ (hourlyStats, khi chia theo giờ).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InsightRow(@JsonProperty("campaign_id") String campaignId, @JsonProperty("adset_id") String adsetId,
            @JsonProperty("date_start") String dateStart, String spend, String impressions, String reach, String clicks,
            List<ActionStat> actions, @JsonProperty("action_values") List<ActionStat> actionValues,
            @JsonProperty("hourly_stats_aggregated_by_advertiser_time_zone") String hourlyStats) {
        public InsightRow {
            campaignId = text(campaignId);
            adsetId = text(adsetId);
            dateStart = text(dateStart);
        }
    }

    /** Một loại hành động trong Insights: vd { action_type: "lead", value: "3" } */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionStat(@JsonProperty("action_type") String actionType, String value) {
        public ActionStat {
            actionType = text(actionType);
        }
    }

    /** Quảng cáo (dùng cho danh sách quảng cáo bị từ chối) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ad(String id, String name, NameOnly campaign, NameOnly adset,
            @JsonProperty("ad_review_feedback") ReviewFeedback reviewFeedback) {
        public Ad {
            id = text(id);
            name = text(name);
        }

        public String campaignName() { return campaign == null ? "" : campaign.name(); }

        public String adsetName() { return adset == null ? "" : adset.name(); }

        /** Lý do bị từ chối (Facebook ghi theo từng chính sách); không có → danh sách rỗng */
        public List<String> reasons() {
            if (reviewFeedback == null || reviewFeedback.global() == null) return List.of();
            return reviewFeedback.global().values().stream().filter(t -> t != null && !t.isEmpty()).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NameOnly(String name) {
        public NameOnly {
            name = text(name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewFeedback(Map<String, String> global) {}

    /** Người dùng của token (me?fields=name) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Me(String name) {
        public Me {
            name = text(name);
        }
    }

    /** Kết quả đổi/gia hạn token (oauth/access_token); accessToken null = Facebook không trả token */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessToken(@JsonProperty("access_token") String accessToken) {}

    /** Kết quả debug_token */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenDebug(TokenInfo data) {}

    /** Thông tin token: còn hợp lệ không, quyền, hết hạn lúc nào (giây, 0 = không hết hạn) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenInfo(@JsonProperty("is_valid") Boolean isValid, List<String> scopes,
            @JsonProperty("expires_at") Long expiresAt, String type, @JsonProperty("app_id") String appId) {
        public TokenInfo {
            scopes = scopes == null ? List.of() : scopes;
            type = text(type);
            appId = text(appId);
        }

        /** Không có trường is_valid thì coi như hợp lệ (như bản Node) */
        public boolean valid() { return !Boolean.FALSE.equals(isValid); }

        /** Hết hạn lúc nào (mili giây); 0 = không hết hạn */
        public long expiresAtMs() { return expiresAt == null ? 0 : expiresAt * 1000; }
    }
}
