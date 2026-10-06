/**
 * Báo cáo lên hệ thống công ty: tạo bản nháp theo mốc 9/12/17/22h, duyệt hoặc tự gửi, cập nhật bản đã có, đồng bộ
 * trạng thái.
 * <p>
 * Đọc trước: CompanyRules (luật thuần, không Spring), rồi CompanyApi (gọi API công ty), rồi CompanyReportService
 * (cửa vào, chạy theo mốc). Sau đó: CompanyDrafts (tạo bản nháp từ số Facebook), CompanySender (gửi, cập nhật, tự
 * gửi + thử lại), CompanyMessages (tin Telegram, Nhật ký).
 */
package com.fbads.company;
