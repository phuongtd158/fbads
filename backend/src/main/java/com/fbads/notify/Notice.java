package com.fbads.notify;

/**
 * Một thông báo, chưa gắn với kênh nào. Mỗi kênh tự đổi sang định dạng của mình.
 *
 * @param topic loại tin: người dùng chọn kênh nào nhận loại tin nào
 * @param html  nội dung viết bằng HTML rút gọn như Telegram: chỉ {@code <b> <i> <a>}, xuống dòng bằng "\n",
 *              ký tự & < > trong dữ liệu đã được đổi thành &amp;amp; &amp;lt; &amp;gt;.
 *              Dòng đầu là tiêu đề (vd "📊 <b>Báo cáo Facebook Ads</b>").
 */
public record Notice(Topic topic, String html) {

    /** Loại tin. Tên enum được lưu trong DB (cột topics của bảng notify_channel), đừng đổi tên. */
    public enum Topic {
        /** Lịch, rule, dừng khẩn, hoàn tác vừa làm gì đó */
        LOG("Nhật ký tự động"),
        /** Cảnh báo: tài khoản QC, quảng cáo bị từ chối, chi tiêu tăng vọt, token sắp hết hạn, vòng tự động lỗi */
        ALERT("Cảnh báo"),
        /** Báo cáo hằng ngày, báo cáo tuần */
        REPORT("Báo cáo"),
        /** Báo cáo lên hệ thống công ty */
        COMPANY("Báo cáo công ty");

        public final String label;

        Topic(String label) { this.label = label; }
    }

    /** Dòng đầu, bỏ thẻ HTML: dùng làm tiêu đề email */
    public String title() {
        String first = html == null ? "" : html.split("\n", 2)[0];
        return toPlain(first).trim();
    }

    /** Toàn bộ nội dung dạng chữ thường (bỏ thẻ HTML): cho kênh không hiểu HTML */
    public String plainText() { return toPlain(html); }

    static String toPlain(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
