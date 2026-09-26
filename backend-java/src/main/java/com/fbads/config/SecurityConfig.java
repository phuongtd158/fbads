package com.fbads.config;

import com.fbads.auth.AuthService;
import com.fbads.auth.PasswordAuthProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Spring Security:
 *  - /api/auth, /api/login, /api/logout, /api/fb/callback: không cần đăng nhập;
 *  - /api/**: cần đăng nhập NẾU đã đặt mật khẩu (AuthorizationManager tự viết, đọc cài đặt mỗi lần nên đặt mật khẩu là có hiệu lực ngay);
 *  - còn lại (giao diện, /actuator/health): mở.
 * Phiên đăng nhập lưu bằng Spring Session JDBC (bảng SPRING_SESSION) nên khởi động lại server không bị đăng xuất.
 * CSRF của Spring tắt vì đã có bộ lọc cùng-origin + bắt buộc JSON (web/ApiFilters), giống bản Node và không phải sửa giao diện.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    AuthenticationManager authenticationManager(PasswordAuthProvider provider) { return new ProviderManager(provider); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthService auth, SecurityContextRepository repo) throws Exception {
        http.csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(rc -> rc.disable())
                .securityContext(sc -> sc.securityContextRepository(repo))
                .headers(h -> h.cacheControl(cc -> cc.disable())) // cache do từng phần tự đặt (API: no-store, file build: 1 năm)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth", "/api/login", "/api/logout", "/api/fb/callback").permitAll()
                        .requestMatchers("/api/**").access((authentication, ctx) -> new AuthorizationDecision(auth.allowed(authentication.get())))
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> unauthorized(res))
                        .accessDeniedHandler((req, res, e) -> unauthorized(res)));
        return http.build();
    }

    private static void unauthorized(HttpServletResponse res) throws java.io.IOException {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write("{\"error\":\"Cần đăng nhập\"}");
    }
}
