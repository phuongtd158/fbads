package com.fbads.security;

import com.fbads.service.AuthService;
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
 *  - /api/auth, /api/login, /api/logout, /api/setup, /api/register, /api/fb/callback: không cần đăng nhập;
 *  - /api/**: cần đăng nhập NẾU đã có tài khoản (AuthorizationManager tự viết: tạo tài khoản đầu tiên là có hiệu lực ngay);
 *    quyền theo từng workspace (OWNER / EDITOR / VIEWER) do WorkspaceFilter kiểm tra sau bước này;
 *  - /ws (WebSocket realtime): như /api;
 *  - còn lại (giao diện, /actuator/health): mở.
 * Phiên đăng nhập lưu ở Redis (Spring Session Data Redis) nên khởi động lại server không bị đăng xuất, chạy nhiều bản vẫn dùng chung phiên.
 * CSRF của Spring tắt vì đã có bộ lọc cùng-origin + bắt buộc JSON (security/ApiFilters), giống bản Node và không phải sửa giao diện.
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
                        .requestMatchers("/api/auth", "/api/login", "/api/logout", "/api/setup", "/api/register", "/api/fb/callback").permitAll()
                        .requestMatchers("/api/**", "/ws", "/ws/**").access((authentication, ctx) -> new AuthorizationDecision(auth.allowed(authentication.get())))
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
