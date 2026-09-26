package com.fbads.auth;

import com.fbads.config.AppProperties;
import com.fbads.settings.SettingsService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Đăng nhập 1 mật khẩu. Mật khẩu lấy từ biến môi trường APP_PASSWORD (ưu tiên) hoặc đặt trong Cài đặt (lưu dạng băm scrypt).
 * Chưa có mật khẩu nào = không cần đăng nhập (chỉ an toàn khi server nghe trên 127.0.0.1).
 */
@Service
public class AuthService {
    public static final String USER = "admin";

    private final AppProperties props;
    private final SettingsService settings;
    private final NodeScryptPasswordEncoder encoder = new NodeScryptPasswordEncoder();

    public AuthService(AppProperties props, SettingsService settings) {
        this.props = props;
        this.settings = settings;
    }

    public boolean envManaged() { return !props.appPassword().isEmpty(); }

    public boolean enabled() { return envManaged() || !settings.get().getPasswordHash().isEmpty(); }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public boolean verify(String pw) {
        if (envManaged()) return MessageDigest.isEqual(sha(pw), sha(props.appPassword()));
        return encoder.matches(pw, settings.get().getPasswordHash());
    }

    public void setPassword(String pw) { settings.update(s -> s.setPasswordHash(encoder.encode(pw))); }

    public static boolean isAuthenticated(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
    }

    /** Được phép gọi API: chưa đặt mật khẩu, hoặc đã đăng nhập */
    public boolean allowed(Authentication a) { return !enabled() || isAuthenticated(a); }
}
