/**
 * Báo cáo lên hệ thống công ty: tạo bản nháp theo mốc 9/12/17/22h, duyệt hoặc tự gửi, cập nhật bản đã có, đồng bộ
 * trạng thái.
 * <p>
 * Đọc trước: CompanyRules (luật thuần, không Spring), rồi CompanyApi (gọi API công ty), rồi CompanyReportService
 * (cửa vào, chạy theo mốc). Sau đó: CompanyDrafts (tạo bản nháp từ số Facebook), CompanySender (gửi, cập nhật, tự
 * gửi + thử lại), CompanyMessages (tin Telegram, Nhật ký).
 * <p>
 * Dữ liệu: CompanyConfig (cấu hình kết nối, bảng company_config), CompanyReport (từng bản báo cáo, bảng
 * company_reports). API cho giao diện: CompanyController; các record Company* còn lại là JSON gửi lên/trả về.
 */
package com.fbads.company;
