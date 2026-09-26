package com.fbads.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Bộ lọc cho /api (chạy TRƯỚC Spring Security, như thứ tự middleware của bản Node):
 *  1. dữ liệu API không bao giờ được cache;
 *  2. chặn gọi chéo từ trang web khác (CSRF): request ghi phải cùng origin và là JSON.
 *     HEAD chỉ được phép trên /api/auth (UptimeRobot… hay dùng HEAD để kiểm tra tool còn sống).
 */
@Configuration
public class ApiFilters {

    static void json(HttpServletResponse res, int status, String error) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + error.replace("\"", "\\\"") + "\"}");
    }

    static class ApiGuard extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilter(HttpServletRequest req) { return !req.getRequestURI().startsWith("/api/"); }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("X-Content-Type-Options", "nosniff");
            String m = req.getMethod();
            if (m.equals("GET") || (m.equals("HEAD") && req.getRequestURI().equals("/api/auth"))) { chain.doFilter(req, res); return; }
            String origin = req.getHeader("Origin");
            if (origin != null) {
                String host;
                try {
                    URI u = URI.create(origin);
                    host = u.getHost() == null ? "" : u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
                } catch (IllegalArgumentException e) {
                    host = "";
                }
                if (!host.equalsIgnoreCase(String.valueOf(req.getHeader("Host")))) { json(res, 403, "Origin không hợp lệ"); return; }
            }
            String ct = req.getContentType();
            if (ct == null || !ct.contains("application/json")) { json(res, 415, "Cần Content-Type: application/json"); return; }
            chain.doFilter(req, res);
        }
    }

    @Bean
    FilterRegistrationBean<ApiGuard> apiGuard() {
        FilterRegistrationBean<ApiGuard> r = new FilterRegistrationBean<>(new ApiGuard());
        r.addUrlPatterns("/api/*");
        r.setOrder(-200); // trước Spring Security (-100) và Spring Session
        return r;
    }
}
