package com.fbads.auth;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mã hoá mật khẩu bằng scrypt theo ĐÚNG định dạng của bản Node ("salt hex:hash hex", N=16384, r=8, p=1, 64 byte)
 * để mật khẩu đã đặt ở bản Node vẫn đăng nhập được sau khi chuyển sang Java.
 * Cài đặt interface PasswordEncoder của Spring Security nên dùng được ở mọi chỗ Spring cần.
 */
public class NodeScryptPasswordEncoder implements PasswordEncoder {
    private static final int N = 16384, R = 8, P = 1, KEY_LEN = 64;
    private static final SecureRandom RND = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    @Override
    public String encode(CharSequence raw) {
        byte[] salt = new byte[16];
        RND.nextBytes(salt);
        return HEX.formatHex(salt) + ":" + HEX.formatHex(hash(raw, salt));
    }

    @Override
    public boolean matches(CharSequence raw, String encoded) {
        if (raw == null || encoded == null) return false;
        String[] parts = encoded.split(":");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return false;
        try {
            return MessageDigest.isEqual(hash(raw, HEX.parseHex(parts[0])), HEX.parseHex(parts[1])); // so sánh thời gian cố định
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] hash(CharSequence raw, byte[] salt) {
        return SCrypt.generate(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), salt, N, R, P, KEY_LEN);
    }
}
