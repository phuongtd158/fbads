package com.fbads.common;

import com.fbads.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretConverterTest {
    private static SecretConverter with(String key) {
        return new SecretConverter(new AppProperties("", "", false, key, "", false, false, "", null, null));
    }

    @Test
    void encryptsAndDecrypts() {
        SecretConverter c = with("khoa-bi-mat-dai");
        String stored = c.convertToDatabaseColumn("EAAB-token");
        assertThat(stored).startsWith("enc:v1:").doesNotContain("EAAB");
        assertThat(stored).isNotEqualTo(c.convertToDatabaseColumn("EAAB-token")); // IV ngẫu nhiên mỗi lần
        assertThat(c.convertToEntityAttribute(stored)).isEqualTo("EAAB-token");
        assertThat(c.convertToDatabaseColumn("")).isEmpty();
    }

    @Test
    void oldPlainValuesStillReadAndNoKeyMeansPlain() {
        assertThat(with("k").convertToEntityAttribute("token-cu")).isEqualTo("token-cu");
        assertThat(with("").convertToDatabaseColumn("token")).isEqualTo("token");
    }

    @Test
    void wrongOrMissingKeyFailsLoudly() {
        String stored = with("khoa-1").convertToDatabaseColumn("token");
        assertThatThrownBy(() -> with("khoa-2").convertToEntityAttribute(stored)).hasMessageContaining("SECRET_KEY");
        assertThatThrownBy(() -> with("").convertToEntityAttribute(stored)).hasMessageContaining("SECRET_KEY");
    }
}
