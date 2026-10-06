package com.fbads.security;

import com.fbads.entity.Role;
import com.fbads.entity.User;
import com.fbads.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chạy sau Spring Security, cho /api và /ws:
 *  1. xác định workspace của request: workspace đang chọn (lưu trong phiên), không còn là thành viên thì lấy workspace đầu tiên;
 *     chế độ mở (chưa có tài khoản nào) = workspace 1 với quyền OWNER;
 *  2. kiểm tra quyền: đọc (GET) thì thành viên nào cũng được; ghi cần EDITOR; cài đặt, Facebook, Telegram, thành viên cần OWNER;
 *  3. gắn workspace vào luồng (WorkspaceContext) trong lúc xử lý request, xong thì gỡ.
 */
@Configuration
public class WorkspaceFilter {
    /** Khoá trong phiên: id workspace đang chọn */
    public static final String SESSION_WS = "fbads.ws";
    /** Thuộc tính request: vai trò (Role) và id người dùng (Long, null ở chế độ mở) */
    public static final String ROLE = "fbads.role", USER_ID = "fbads.userId";

    /** Không cần workspace: đăng nhập / đăng ký, callback của Facebook (tự lấy workspace từ state) */
    static final Set<String> NO_WORKSPACE = Set.of("/api/auth", "/api/health", "/api/login", "/api/logout", "/api/setup", "/api/register", "/api/fb/callback");
    /** Việc của riêng người dùng, không phụ thuộc quyền trong workspace đang chọn */
    static final Set<String> PERSONAL = Set.of("/api/password", "/api/workspaces", "/api/workspaces/switch");
    /** Ghi vào đây cần quyền OWNER */
    static final List<String> OWNER_PREFIXES = List.of("/api/settings", "/api/fb/", "/api/telegram/", "/api/members", "/api/workspace", "/api/company/config");

    /** Quyền tối thiểu để gọi; null = không cần kiểm tra quyền */
    static Role required(String method, String path) {
        if (PERSONAL.contains(path)) return null;
        if (method.equals("GET") || method.equals("HEAD")) return Role.VIEWER;
        for (String p : OWNER_PREFIXES) if (path.startsWith(p)) return Role.OWNER;
        return Role.EDITOR;
    }

    public static Role role(HttpServletRequest req) { return (Role) req.getAttribute(ROLE); }

    public static Long userId(HttpServletRequest req) { return (Long) req.getAttribute(USER_ID); }

    static class Filter extends OncePerRequestFilter {
        private final AuthService auth;

        Filter(AuthService auth) { this.auth = auth; }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest req) {
            String p = req.getRequestURI();
            return !(p.startsWith("/api/") || p.equals("/ws") || p.startsWith("/ws/")) || NO_WORKSPACE.contains(p);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            String path = req.getRequestURI();
            Long ws;
            Role role;
            if (auth.openMode()) {
                ws = WorkspaceContext.DEFAULT;
                role = Role.OWNER;
            } else {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                if (!AuthService.isAuthenticated(a)) { chain.doFilter(req, res); return; } // Spring Security đã chặn phần cần đăng nhập
                Optional<User> user = auth.user(a.getName());
                if (user.isEmpty()) { // tài khoản không còn
                    HttpSession s = req.getSession(false);
                    if (s != null) s.invalidate();
                    ApiFilters.json(res, 401, "Cần đăng nhập");
                    return;
                }
                long uid = user.get().getId();
                req.setAttribute(USER_ID, uid);
                HttpSession session = req.getSession();
                Long chosen = (Long) session.getAttribute(SESSION_WS);
                role = chosen == null ? null : auth.role(chosen, uid);
                ws = role == null ? null : chosen;
                if (ws == null) { // chưa chọn, hoặc đã bị gỡ khỏi workspace đang chọn → workspace đầu tiên còn lại
                    var first = auth.memberships(uid).stream().findFirst();
                    ws = first.map(AuthService.Membership::id).orElse(null);
                    role = first.map(AuthService.Membership::role).orElse(null);
                    if (ws == null) session.removeAttribute(SESSION_WS); else session.setAttribute(SESSION_WS, ws);
                }
            }
            Role need = required(req.getMethod(), path);
            if (need != null) {
                if (ws == null) { ApiFilters.json(res, 403, "Bạn chưa thuộc workspace nào. Nhờ chủ workspace thêm bạn vào, hoặc tạo workspace mới."); return; }
                if (!role.atLeast(need)) { ApiFilters.json(res, 403, deny(need)); return; }
            }
            req.setAttribute(ROLE, role);
            if (ws == null) { chain.doFilter(req, res); return; }
            long id = ws;
            try {
                WorkspaceContext.call(id, () -> {
                    try {
                        chain.doFilter(req, res);
                    } catch (IOException | ServletException e) {
                        throw new Wrapped(e);
                    }
                    return null;
                });
            } catch (Wrapped w) {
                if (w.getCause() instanceof IOException io) throw io;
                throw (ServletException) w.getCause();
            }
        }

        static String deny(Role need) {
            return need == Role.OWNER ? "Chỉ chủ workspace (OWNER) được làm việc này." : "Bạn chỉ có quyền xem trong workspace này.";
        }
    }

    private static final class Wrapped extends RuntimeException {
        Wrapped(Exception e) { super(e); }
    }

    @Bean
    FilterRegistrationBean<Filter> workspaceFilterRegistration(AuthService auth) {
        FilterRegistrationBean<Filter> r = new FilterRegistrationBean<>(new Filter(auth));
        r.addUrlPatterns("/api/*", "/ws", "/ws/*");
        r.setOrder(0); // sau Spring Security (-100): lúc này đã biết ai đang đăng nhập
        return r;
    }
}
