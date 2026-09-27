package com.fbads.logging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Cách in tham số/kết quả: gọn, không lộ mật khẩu hay token */
class CallLoggerTest {
    private final CallLogger calls = new CallLogger(
            new StaticListableBeanFactory(Map.of("json", JsonMapper.builder().build())).getBeanProvider(JsonMapper.class));

    @Test
    void secretsAreMasked() {
        assertThat(calls.args(new String[]{"pw", "newPassword", "code", "id"}, new Object[]{"MatKhau@2026", "Abc@12345", "fb-code", "mock_1"}))
                .isEqualTo("pw=***, newPassword=***, code=***, id=\"mock_1\"");
        String settings = calls.render(null, Map.of("accessToken", "EAAB123", "telegramToken", "123:abc", "fbAppSecret", "s3cr3t",
                "passwordHash", "scrypt$xyz", "nested", Map.of("token", "t"), "timezone", "Asia/Ho_Chi_Minh"));
        assertThat(settings).doesNotContain("EAAB123", "123:abc", "s3cr3t", "scrypt$xyz", "\"t\"").contains("Asia/Ho_Chi_Minh", "***");
    }

    @Test
    void valuesAreShort() {
        assertThat(calls.render("x", null)).isEqualTo("null");
        assertThat(calls.render("n", 42)).isEqualTo("42");
        assertThat(calls.render("o", Optional.empty())).isEqualTo("Optional.empty");
        Consumer<String> fill = s -> {};
        assertThat(calls.render("fill", fill)).isEqualTo("λ");
        assertThat(calls.render("ids", List.of(1, 2, 3, 4, 5, 6, 7))).isEqualTo("[1, 2, 3, 4, 5, … (7 phần tử)]");
        String longText = calls.render("s", "a".repeat(1000));
        assertThat(longText).hasSize(CallLogger.MAX + "…(+702)".length()).endsWith("…(+702)");
    }
}
