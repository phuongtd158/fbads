/**
 * Gửi thông báo ra ngoài (Telegram, sau này Gmail, Zalo…), không phụ thuộc kênh nào.
 * <p>
 * Hình dung như phòng thư của văn phòng:
 * <ul>
 *   <li>{@link com.fbads.notify.Notice}: lá thư (nội dung + loại tin), chưa gắn với kênh nào;</li>
 *   <li>{@link com.fbads.notify.NotifyChannel}: một "hãng chuyển phát". Mỗi kênh là một class trong gói
 *       {@code channel}, tự biết gói thư theo kiểu của mình và gửi đi;</li>
 *   <li>{@link com.fbads.notify.Notifier}: phòng thư. Nhận sự kiện, soạn thư, chọn kênh của workspace rồi giao.</li>
 * </ul>
 * Thêm kênh mới = thêm một class cài {@code NotifyChannel} trong gói {@code channel}. Không phải sửa nơi phát thông báo.
 * <p>
 * Đọc trước: Notice, NotifyChannel, rồi Notifier.
 */
package com.fbads.notify;
