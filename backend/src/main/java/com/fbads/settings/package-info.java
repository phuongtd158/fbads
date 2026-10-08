/**
 * Cài đặt của mỗi workspace (bảng app_settings): token Facebook, tài khoản quảng cáo, giờ báo cáo, chế độ Dùng thử…
 * <p>
 * Đọc theo thứ tự: SettingsController (/state, /settings) → SettingsService (đọc, sửa, che token khi trả về bằng
 * PublicSettings) → SettingsValidator → AppSettings. DataImporter nhập dữ liệu từ file JSON của bản Node lúc khởi động.
 */
package com.fbads.settings;
