package com.fbads.common;

import com.fbads.config.AppProperties;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mã hoá bí mật (token Facebook, App Secret, token Telegram) trước khi ghi vào DB: AES-256-GCM, khoá lấy từ SECRET_KEY.
 * Trong DB lưu dạng "enc:v1:<base64(iv 12 byte + dữ liệu mã hoá + tag)>"; ai đọc được DB (bản sao lưu, log SQL…) cũng không dùng được token.
 *  - Chưa đặt SECRET_KEY: ghi nguyên văn như trước (vẫn chạy được, lúc khởi động có cảnh báo).
 *  - Đọc: giá trị không có tiền tố "enc:" là dữ liệu cũ chưa mã hoá → dùng nguyên văn; lần lưu sau sẽ được mã hoá.
 * Là bean Spring (Hibernate lấy converter qua Spring) nên đọc được cấu hình.
 */
@Component
@Converter
public class SecretConverter implements AttributeConverter<String, String> {
    static final String PREFIX = "enc:v1:";
    private static final SecureRandom RND = new SecureRandom();

    private final SecretKeySpec key;

    public SecretConverter(AppProperties props) { this.key = keyOf(props.secretKey()); }

    /** SECRET_KEY bất kỳ độ dài → khoá AES 256 bit (SHA-256). null = không mã hoá */
    static SecretKeySpec keyOf(String secret) {
        if (secret == null || secret.isBlank()) return null;
        try {
            return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean enabled() { return key != null; }

    public static boolean isEncrypted(String v) { return v != null && v.startsWith(PREFIX); }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null || plain.isEmpty() || key == null) return plain;
        try {
            byte[] iv = new byte[12];
            RND.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hoá được bí mật", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (!isEncrypted(stored)) return stored;
        if (key == null) throw new IllegalStateException("Token trong DB đã được mã hoá nhưng server chưa đặt SECRET_KEY. Đặt lại đúng SECRET_KEY cũ rồi khởi động lại.");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Không giải mã được token trong DB: SECRET_KEY khác với lúc đã lưu.", e);
        }
    }
}
