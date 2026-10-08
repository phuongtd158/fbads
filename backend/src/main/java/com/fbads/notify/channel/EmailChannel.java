package com.fbads.notify.channel;

import com.fbads.notify.ConfigField;
import com.fbads.notify.Notice;
import com.fbads.notify.NotifyChannel;
import com.fbads.notify.SendResult;
import com.fbads.notify.SendResult.Recipient;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Kênh Gmail: gửi email qua máy chủ SMTP của Gmail bằng tài khoản Gmail của người dùng và "mật khẩu ứng dụng"
 * (Google không cho đăng nhập SMTP bằng mật khẩu Gmail thường).
 * Mỗi người nhận một email riêng, để biết ai nhận được, ai lỗi (như Telegram).
 * <p>
 * Cấu hình: {@code {"username": "ban@gmail.com", "password": "abcd efgh ijkl mnop", "to": "a@x.com, b@y.com"}}.
 * <p>
 * Gmail giới hạn khoảng 500 thư/ngày với tài khoản thường, nên kênh mới mặc định không nhận Nhật ký tự động.
 */
@Component
@Order(2) // thứ tự trên form Thêm kênh
public class EmailChannel implements NotifyChannel {
    public static final String TYPE = "email";
    public static final int MAX_RECIPIENTS = 10;

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");
    private static final Pattern SPLIT = Pattern.compile("[\\s,;]+");
    private static final Pattern SMTP_CODE = Pattern.compile("^([45]\\d\\d)\\b");

    /** Máy chủ SMTP. Test đổi sang máy chủ giả (GreenMail), không mã hoá */
    private volatile String host = "smtp.gmail.com";
    private volatile int port = 587;
    private volatile boolean startTls = true;

    @Override
    public String type() { return TYPE; }

    @Override
    public String label() { return "Gmail"; }

    @Override
    public List<ConfigField> fields() {
        return List.of(
                new ConfigField("username", "Gmail gửi đi", false, "vd ten.cua.ban@gmail.com", "Tài khoản Gmail dùng để gửi thư."),
                new ConfigField("password", "Mật khẩu ứng dụng", true, "16 ký tự, vd abcd efgh ijkl mnop",
                        "Không phải mật khẩu Gmail thường. Tạo ở myaccount.google.com/apppasswords (cần bật xác minh 2 bước)."),
                new ConfigField("to", "Gửi tới", false, "vd sep@congty.com, ban@gmail.com",
                        "Nhiều người thì cách nhau bằng dấu phẩy (tối đa " + MAX_RECIPIENTS + ")."));
    }

    @Override
    public String help() {
        return "<ol><li>Vào <b>myaccount.google.com</b> → Bảo mật, bật <b>Xác minh 2 bước</b>.</li>"
                + "<li>Mở <code>myaccount.google.com/apppasswords</code>, đặt tên (vd \"fbads\") rồi bấm Tạo: Google hiện mật khẩu 16 ký tự.</li>"
                + "<li>Dán mật khẩu đó vào ô <b>Mật khẩu ứng dụng</b>, nhập Gmail của bạn và địa chỉ người nhận.</li>"
                + "<li>Gmail tài khoản thường gửi tối đa khoảng 500 thư/ngày: nên để kênh này nhận Cảnh báo và Báo cáo, "
                + "không nhận Nhật ký tự động.</li></ol>";
    }

    @Override
    public List<Notice.Topic> defaultTopics() { return List.of(Notice.Topic.ALERT, Notice.Topic.REPORT, Notice.Topic.COMPANY); }

    @Override
    public Map<String, String> validate(ObjectNode config) {
        Map<String, String> errors = new LinkedHashMap<>();
        String user = config.path("username").asString("").trim();
        String pass = config.path("password").asString("").replace(" ", ""); // Google hiện mật khẩu có dấu cách
        List<String> to = recipients(config.path("to").asString(""));

        if (user.isEmpty()) errors.put("username", "Nhập địa chỉ Gmail dùng để gửi");
        else if (!EMAIL.matcher(user).matches()) errors.put("username", "Địa chỉ email không đúng dạng");
        if (pass.isEmpty()) errors.put("password", "Nhập mật khẩu ứng dụng của Gmail");
        if (to.isEmpty()) errors.put("to", "Cần ít nhất một địa chỉ nhận");
        else if (to.size() > MAX_RECIPIENTS) errors.put("to", "Tối đa " + MAX_RECIPIENTS + " địa chỉ (đang nhập " + to.size() + ")");
        else to.stream().filter(a -> !EMAIL.matcher(a).matches()).findFirst()
                .ifPresent(a -> errors.put("to", "“" + a + "” không phải địa chỉ email hợp lệ"));

        config.put("username", user);
        config.put("password", pass);
        config.put("to", String.join(", ", to));
        return errors;
    }

    /** Cho kiểm thử: trỏ tới máy chủ SMTP giả */
    public void setSmtp(String host, int port, boolean startTls) {
        this.host = host;
        this.port = port;
        this.startTls = startTls;
    }

    /** Gửi cho từng người nhận. Thiếu tài khoản, mật khẩu hoặc người nhận = chưa cài, không gửi gì. */
    @Override
    public SendResult send(Notice notice, JsonNode config) {
        String user = config.path("username").asString("");
        String pass = config.path("password").asString("");
        List<String> to = recipients(config.path("to").asString(""));
        if (user.isEmpty() || pass.isEmpty() || to.isEmpty()) return SendResult.notConfigured();

        JavaMailSenderImpl mail = sender(user, pass);
        List<Recipient> results = new ArrayList<>();
        for (String address : to) results.add(sendOne(mail, user, address, notice));
        for (Recipient r : results) if (!r.ok()) log.warn("Gmail lỗi ({}): {}", r.id(), r.error());
        return new SendResult(true, results);
    }

    private JavaMailSenderImpl sender(String user, String pass) {
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost(host);
        mail.setPort(port);
        mail.setUsername(user);
        mail.setPassword(pass);
        mail.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = mail.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        p.put("mail.smtp.starttls.required", String.valueOf(startTls));
        // Gmail treo thì báo lỗi sau 10 giây, không giữ chân vòng tự động
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        return mail;
    }

    private Recipient sendOne(JavaMailSenderImpl mail, String from, String to, Notice notice) {
        try {
            MimeMessage m = mail.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, true, StandardCharsets.UTF_8.name());
            h.setFrom(new InternetAddress(from, "Facebook Ads Auto Tool", StandardCharsets.UTF_8.name()));
            h.setTo(to);
            h.setSubject(notice.title());
            // HTML rút gọn của Notice + bản chữ thường cho trình đọc thư không hiện HTML
            h.setText(notice.plainText(), "<div style=\"font-family:sans-serif;font-size:14px;line-height:1.6\">"
                    + notice.html().replace("\n", "<br>") + "</div>");
            mail.send(m);
            return new Recipient(to, true, null, false);
        } catch (Exception e) {
            return failure(to, e);
        }
    }

    /** Lỗi gửi thư → câu dễ hiểu + có nên thử lại không */
    static Recipient failure(String to, Exception e) {
        if (e instanceof MailAuthenticationException || causedBy(e, AuthenticationFailedException.class) != null)
            return new Recipient(to, false, "Gmail từ chối đăng nhập: kiểm tra địa chỉ Gmail và mật khẩu ứng dụng "
                    + "(không dùng được mật khẩu Gmail thường).", false);
        SendFailedException failed = causedBy(e, SendFailedException.class);
        if (failed != null) {
            // máy chủ SMTP trả mã 3 chữ số ở đầu câu lỗi. 4xx: tạm thời (quá tải, vượt giới hạn tạm thời);
            // 5xx: cố định (địa chỉ không tồn tại, bị chặn, hết hạn mức ngày)
            String msg = firstLine(failed.getMessage());
            var code = SMTP_CODE.matcher(msg);
            if (code.find()) return new Recipient(to, false, "Gmail báo lỗi: " + msg, code.group(1).startsWith("4"));
            return new Recipient(to, false, "Địa chỉ nhận không hợp lệ hoặc bị từ chối.", false);
        }
        return new Recipient(to, false, "Không kết nối được tới Gmail. Kiểm tra mạng internet.", true);
    }

    private static <T extends Throwable> T causedBy(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return type.cast(t);
            if (t instanceof jakarta.mail.MessagingException me && me.getNextException() != null && type.isInstance(me.getNextException()))
                return type.cast(me.getNextException());
        }
        return null;
    }

    private static String firstLine(String s) { return s == null ? "" : s.strip().split("\n", 2)[0]; }

    /** Danh sách người nhận: cách nhau bằng dấu phẩy / chấm phẩy / khoảng trắng, bỏ trùng (không phân biệt hoa thường) */
    static List<String> recipients(String v) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String raw : SPLIT.split(v == null ? "" : v)) {
            String a = raw.trim();
            if (!a.isEmpty()) seen.putIfAbsent(a.toLowerCase(), a);
        }
        return new ArrayList<>(seen.values());
    }
}
