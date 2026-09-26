package com.fbads.facebook;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Số liệu của một camp/nhóm QC trong một khoảng thời gian (như metricsFrom của lib/fb.js).
 * cpa/roas = null khi chưa tính được (chưa có kết quả / chưa chi tiêu).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Metrics(
        double spend, double impressions, double reach, double clicks, double results,
        Double cpa, double revenue, Double roas,
        double conversations, double checkouts, double leads, double leadsOnMeta, double comments) {

    public static final Metrics EMPTY = new Metrics(0, 0, 0, 0, 0, null, 0, null, 0, 0, 0, 0, 0);
}
