/**
 * Facebook Marketing API (bản Java của lib/fb.js), chia theo việc:
 * <ul>
 *   <li>{@link com.fbads.service.facebook.FacebookObjects}: danh sách camp + nhóm QC kèm số hôm nay. Đọc file này trước.</li>
 *   <li>{@link com.fbads.service.facebook.FacebookInsights}: số theo khoảng ngày, xu hướng từng ngày, chi tiêu theo giờ.</li>
 *   <li>{@link com.fbads.service.facebook.FacebookActions}: bật/tắt, đổi ngân sách.</li>
 *   <li>{@link com.fbads.service.facebook.FacebookAuth}: token, đăng nhập Facebook, kiểm tra kết nối.</li>
 *   <li>{@link com.fbads.service.facebook.FacebookHealth}: tình trạng tài khoản, quảng cáo bị từ chối.</li>
 * </ul>
 * Ba class dùng chung bên dưới: FacebookGraph (gọi Graph API), FacebookState (số đã tải của từng workspace, cache
 * Redis), FacebookParse (đọc số trong câu trả lời). Bật dữ liệu giả (Cài đặt) thì không gọi Facebook mà dùng
 * client.MockAds.
 */
package com.fbads.service.facebook;
