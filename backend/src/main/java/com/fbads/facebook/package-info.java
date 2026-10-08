/**
 * Facebook Marketing API (bản Java của lib/fb.js), chia theo việc:
 * <ul>
 *   <li>{@link com.fbads.facebook.FacebookObjects}: danh sách camp + nhóm QC kèm số hôm nay. Đọc file này trước.</li>
 *   <li>{@link com.fbads.facebook.FacebookInsights}: số theo khoảng ngày, xu hướng từng ngày, chi tiêu theo giờ.</li>
 *   <li>{@link com.fbads.facebook.FacebookActions}: bật/tắt, đổi ngân sách.</li>
 *   <li>{@link com.fbads.facebook.FacebookAuth}: token, đăng nhập Facebook, kiểm tra kết nối.</li>
 *   <li>{@link com.fbads.facebook.FacebookHealth}: tình trạng tài khoản, quảng cáo bị từ chối.</li>
 * </ul>
 * Ba class dùng chung bên dưới: FacebookGraph (gọi Graph API), FacebookState (số đã tải của từng workspace, cache
 * Redis), FacebookParse (đọc số trong câu trả lời). GraphData là các record đọc JSON Facebook trả về.
 * <p>
 * Tầng thấp nhất: GraphClient gửi HTTP tới Facebook (thử lại, giới hạn số lần gọi qua RateLimits), FbException đổi
 * lỗi Facebook thành câu dễ hiểu. Bật dữ liệu giả (Cài đặt) thì không gọi Facebook mà dùng MockAds.
 * FacebookController là API kết nối (token, đăng nhập bằng Facebook, chọn tài khoản).
 */
package com.fbads.facebook;
