package com.fbads.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Mã hoá mật khẩu người dùng: mật khẩu mới dùng bcrypt ("{bcrypt}…"). Mật khẩu cũ của bản Node (scrypt) mang tiền tố
 * "{scrypt-node}" vẫn đăng nhập được, và được đổi sang bcrypt ở lần đăng nhập đúng đầu tiên (upgradeEncoding).
 */
public final class Passwords {
    public static final String LEGACY = "scrypt-node";

    private Passwords() {}

    public static PasswordEncoder encoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(), LEGACY, new NodeScryptPasswordEncoder()));
    }
}
