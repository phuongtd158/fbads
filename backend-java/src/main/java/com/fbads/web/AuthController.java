package com.fbads.web;

import com.fbads.auth.AuthService;
import com.fbads.auth.LoginAttempts;
import com.fbads.validation.Requests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Đăng nhập / đăng xuất / đổi mật khẩu. */
@RestController
@RequestMapping("/api")
public class AuthController {
    private static final Map<String, Object> OK = Map.of("ok", true);

    private final AuthService auth;
    private final LoginAttempts attempts;
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepo;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AuthController(AuthService auth, LoginAttempts attempts, AuthenticationManager authManager, SecurityContextRepository contextRepo,
                          FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.auth = auth;
        this.attempts = attempts;
        this.authManager = authManager;
        this.contextRepo = contextRepo;
        this.sessions = sessions;
    }

    @GetMapping("/auth")
    Map<String, Object> status(Authentication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("required", auth.enabled());
        m.put("authed", auth.allowed(a));
        m.put("envManaged", auth.envManaged());
        return m;
    }

    /** Đăng nhập thành công: lưu SecurityContext vào phiên (Spring Session ghi xuống DB) và đổi mã phiên (chống session fixation) */
    private void signIn(Authentication a, HttpServletRequest req, HttpServletResponse res) {
        req.getSession(true);
        req.changeSessionId();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(a);
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, req, res);
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody(required = false) JsonNode body, HttpServletRequest req, HttpServletResponse res) throws InterruptedException {
        int wait = attempts.lockedMinutes(req);
        if (wait > 0) return ApiExceptionHandler.error(429, "Nhập sai quá nhiều lần. Thử lại sau " + wait + " phút.");
        if (!auth.enabled()) return ResponseEntity.ok(OK);
        String pw = body == null ? "" : body.path("password").asString("");
        try {
            Authentication a = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(AuthService.USER, pw));
            attempts.clear(req);
            signIn(a, req, res);
            return ResponseEntity.ok(OK);
        } catch (AuthenticationException e) {
            attempts.recordFail(req);
            Thread.sleep(600); // làm chậm dò mật khẩu
            return ApiExceptionHandler.error(401, "Sai mật khẩu");
        }
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return OK;
    }

    @PostMapping("/password")
    ResponseEntity<?> password(@Valid @RequestBody Requests.PasswordChange b, HttpServletRequest req, HttpServletResponse res) {
        if (auth.envManaged())
            return ApiExceptionHandler.error(400, "Mật khẩu đang được đặt bằng biến môi trường APP_PASSWORD trên server, không đổi ở đây được.");
        String cur = b.currentPassword() == null ? "" : b.currentPassword();
        if (!cur.isEmpty() && cur.equals(b.newPassword())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Mật khẩu mới phải khác mật khẩu hiện tại");
            body.put("errors", Map.of("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại"));
            return ResponseEntity.badRequest().body(body);
        }
        if (auth.enabled() && !auth.verify(cur)) return ApiExceptionHandler.error(400, "Mật khẩu hiện tại không đúng.");
        auth.setPassword(b.newPassword());
        // Đăng xuất mọi thiết bị khác: xoá các phiên của người dùng này (Spring Session đánh chỉ mục theo tên người dùng)
        HttpSession mine = req.getSession(false);
        for (String id : sessions.findByPrincipalName(AuthService.USER).keySet()) if (mine == null || !id.equals(mine.getId())) sessions.deleteById(id);
        signIn(UsernamePasswordAuthenticationToken.authenticated(AuthService.USER, null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ADMIN")), req, res);
        return ResponseEntity.ok(OK);
    }
}
