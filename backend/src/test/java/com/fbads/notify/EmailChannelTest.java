package com.fbads.notify;

import com.fbads.notify.channel.EmailChannel;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kênh Gmail gửi tới máy chủ SMTP giả (GreenMail), không bao giờ gửi thư thật. */
class EmailChannelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Notice NOTICE = new Notice(Notice.Topic.ALERT, "⚠️ <b>Chi tiêu tăng vọt</b>\nCamp &lt;A&gt;: 500.000đ");

    GreenMail smtp;
    EmailChannel email;

    @BeforeEach
    void setUp() {
        smtp = new GreenMail(ServerSetupTest.SMTP.dynamicPort())
                .withConfiguration(GreenMailConfiguration.aConfig().withUser("ban@gmail.com", "ban@gmail.com", "matkhauungdung"));
        smtp.start();
        email = new EmailChannel();
        email.setSmtp("127.0.0.1", smtp.getSmtp().getPort(), false);
    }

    @AfterEach
    void tearDown() { smtp.stop(); }

    private static ObjectNode cfg(String user, String pass, String to) {
        return JSON.valueToTree(Map.of("username", user, "password", pass, "to", to));
    }

    @Test
    void sendsOneEmailPerRecipientWithTitleAsSubject() throws Exception {
        SendResult r = email.send(NOTICE, cfg("ban@gmail.com", "matkhauungdung", "sep@congty.com, a@b.vn"));
        assertThat(r.recipients()).extracting(SendResult.Recipient::ok).containsExactly(true, true);

        MimeMessage[] got = smtp.getReceivedMessages();
        assertThat(got).hasSize(2);
        assertThat(got[0].getSubject()).isEqualTo("⚠️ Chi tiêu tăng vọt");
        String body = GreenMailUtil.getBody(got[0]);
        assertThat(body).contains("Camp <A>: 500.000"); // bản chữ thường
        assertThat(body).contains("<b>Chi ti"); // bản HTML (giữ in đậm)
    }

    @Test
    void wrongPasswordIsPermanent() {
        SendResult r = email.send(NOTICE, cfg("ban@gmail.com", "sai", "sep@congty.com"));
        SendResult.Recipient one = r.recipients().getFirst();
        assertThat(one.ok()).isFalse();
        assertThat(one.error()).contains("mật khẩu ứng dụng");
        assertThat(one.retryable()).isFalse();
        assertThat(smtp.getReceivedMessages()).isEmpty();
    }

    @Test
    void unreachableServerIsRetryable() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) { closed = s.getLocalPort(); }
        email.setSmtp("127.0.0.1", closed, false);
        SendResult.Recipient one = email.send(NOTICE, cfg("ban@gmail.com", "matkhauungdung", "sep@congty.com")).recipients().getFirst();
        assertThat(one.ok()).isFalse();
        assertThat(one.retryable()).isTrue();
    }

    @Test
    void missingConfigSendsNothing() {
        assertThat(email.send(NOTICE, cfg("ban@gmail.com", "", "sep@congty.com")).configured()).isFalse();
    }

    @Test
    void validateChecksAndNormalizes() {
        ObjectNode ok = cfg(" ban@gmail.com ", "abcd efgh ijkl mnop", "a@b.vn; A@B.vn  c@d.com");
        assertThat(email.validate(ok)).isEmpty();
        assertThat(ok.get("username").asString()).isEqualTo("ban@gmail.com");
        assertThat(ok.get("password").asString()).isEqualTo("abcdefghijklmnop");
        assertThat(ok.get("to").asString()).isEqualTo("a@b.vn, c@d.com");

        Map<String, String> bad = email.validate(cfg("ban", "", "a@b.vn, khongphaiemail"));
        assertThat(bad).containsKeys("username", "password", "to");
        assertThat(bad.get("to")).contains("khongphaiemail");
        assertThat(email.validate(cfg("ban@gmail.com", "x", ""))).containsKey("to");
    }
}
