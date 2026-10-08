/**
 * Camp và nhóm quảng cáo: AdObject là một camp/nhóm QC kèm số liệu (Metrics), dùng chung cho mọi tính năng.
 * <p>
 * Đọc theo thứ tự: ObjectsController (API) → ObjectService (bật/tắt, đổi ngân sách tay, xu hướng, có ghi nhật ký)
 * → AdObject. Lấy dữ liệu từ Facebook nằm ở service/facebook. ObjectsList, Insights, Trend là JSON trả về.
 */
package com.fbads.ads;
