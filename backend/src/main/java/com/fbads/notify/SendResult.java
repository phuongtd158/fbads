package com.fbads.notify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kết quả gửi một thông báo: từng người nhận được hay lỗi.
 *
 * @param configured false = chưa cài kênh nào (hoặc kênh thiếu cấu hình), không gửi gì cả
 * @param recipients kết quả từng người nhận
 */
public record SendResult(boolean configured, List<Recipient> recipients) {

    /**
     * Kết quả của một người nhận.
     *
     * @param id        người nhận, hiện trên giao diện (vd "Telegram · 123456789")
     * @param error     lý do lỗi, dễ hiểu, không chứa token
     * @param retryable lỗi tạm thời, thử lại sau có thể được (không đưa ra giao diện)
     */
    public record Recipient(String id, boolean ok, String error, boolean retryable) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("ok", ok);
            if (error != null) m.put("error", error);
            return m;
        }
    }

    /** Phản hồi cho giao diện: mã HTTP + nội dung */
    public record Reply(int status, Map<String, Object> body) {}

    public static SendResult notConfigured() { return new SendResult(false, List.of()); }

    public boolean anyOk() { return recipients.stream().anyMatch(Recipient::ok); }

    /** Thêm tên kênh trước mỗi người nhận: "123" → "Telegram · 123" */
    public SendResult named(String channelName) {
        return new SendResult(configured, recipients.stream()
                .map(r -> new Recipient(channelName + " · " + r.id(), r.ok(), r.error(), r.retryable())).toList());
    }

    /** Gộp kết quả của nhiều kênh thành một */
    public static SendResult merge(List<SendResult> all) {
        List<Recipient> rs = new ArrayList<>();
        boolean configured = false;
        for (SendResult r : all) {
            configured |= r.configured();
            rs.addAll(r.recipients());
        }
        return new SendResult(configured, rs);
    }

    /**
     * Chưa cài, hoặc ít nhất một người đã nhận: coi là xong (không gửi lại cho người đã nhận).
     * Không ai nhận được thì ném lỗi: tạm thời nếu có người lỗi tạm thời, còn lại là lỗi cố định.
     */
    public void throwIfNobodyGotIt() {
        if (!configured || anyOk()) return;
        String why = recipients.getFirst().error();
        if (recipients.stream().anyMatch(Recipient::retryable)) throw new NotifyFailure.Retryable(why);
        throw new NotifyFailure.Permanent(why);
    }

    /**
     * Kết quả gửi → phản hồi: gửi được cho ít nhất một người thì 200, không ai nhận được thì 400.
     *
     * @param notConfigured câu báo lỗi khi chưa cài kênh nào
     */
    public Reply reply(String notConfigured) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!configured) { body.put("error", notConfigured); return new Reply(400, body); }
        List<Map<String, Object>> rs = recipients.stream().map(Recipient::toJson).toList();
        long sent = recipients.stream().filter(Recipient::ok).count();
        if (sent > 0) {
            body.put("ok", true);
            body.put("sent", sent);
            body.put("total", recipients.size());
            body.put("results", rs);
            return new Reply(200, body);
        }
        String why = recipients.getFirst().error();
        body.put("error", recipients.size() > 1 ? "Không gửi được cho ai cả. Lỗi đầu tiên: " + why : "Gửi thất bại: " + why);
        body.put("results", rs);
        return new Reply(400, body);
    }
}
