package com.fbads.controller;

import com.fbads.dto.Requests;
import com.fbads.entity.Role;
import com.fbads.entity.User;
import com.fbads.security.PasswordAuthProvider;
import com.fbads.security.WorkspaceFilter;
import com.fbads.service.AuthService;
import com.fbads.service.LoginAttempts;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Đăng nhập / đăng xuất / tạo tài khoản đầu tiên / tự đăng ký / đổi mật khẩu. */
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

    private static String str(JsonNode b, String k) { return b == null ? "" : b.path(k).asString(""); }

    /**
     * Trạng thái đăng nhập cho giao diện:
     *  required/authed/envManaged như trước; setup = chưa có tài khoản nào (mời tạo tài khoản đầu tiên);
     *  signup = được tự đăng ký; user; workspace đang chọn (kèm role); workspaces = mọi workspace của người này.
     */
    @GetMapping("/auth")
    Map<String, Object> status(Authentication a, HttpServletRequest req) {
        boolean open = auth.openMode();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("required", !open);
        m.put("authed", auth.allowed(a));
        m.put("setup", open);
        m.put("signup", auth.signupAllowed());
        Optional<User> user = open || !AuthService.isAuthenticated(a) ? Optional.empty() : auth.user(a.getName());
        m.put("envManaged", user.map(auth::isEnvAdmin).orElse(false));
        m.put("user", user.map(AuthService::userJson).orElse(null));
        List<AuthService.Membership> list = open
                ? List.of(new AuthService.Membership(1, auth.workspace(1).map(w -> w.getName()).orElse("Workspace chính"), Role.OWNER))
                : user.map(u -> auth.memberships(u.getId())).orElse(List.of());
        HttpSession s = req.getSession(false);
        Long chosen = s == null ? null : (Long) s.getAttribute(WorkspaceFilter.SESSION_WS);
        AuthService.Membership cur = list.stream().filter(x -> chosen != null && x.id() == chosen).findFirst().orElse(list.isEmpty() ? null : list.getFirst());
        m.put("workspace", cur);
        m.put("workspaces", list);
        return m;
    }

    /** Đăng nhập thành công: lưu SecurityContext vào phiên (Spring Session ghi vào Redis) và đổi mã phiên (chống session fixation) */
    private void signIn(Authentication a, HttpServletRequest req, HttpServletResponse res) {
        req.getSession(true);
        req.changeSessionId();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(a);
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, req, res);
    }

    /** { username, password }. Bỏ trống username = "admin" (giao diện cũ chỉ gửi mật khẩu). */
    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody(required = false) JsonNode body, HttpServletRequest req, HttpServletResponse res) throws InterruptedException {
        int wait = attempts.lockedMinutes(req);
        if (wait > 0) return ApiExceptionHandler.error(429, "Nhập sai quá nhiều lần. Thử lại sau " + wait + " phút.");
        if (auth.openMode()) return ResponseEntity.ok(OK);
        String username = str(body, "username").isBlank() ? AuthService.ADMIN : str(body, "username");
        try {
            Authentication a = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(AuthService.normalize(username), str(body, "password")));
            attempts.clear(req);
            signIn(a, req, res);
            return ResponseEntity.ok(OK);
        } catch (AuthenticationException e) {
            attempts.recordFail(req);
            Thread.sleep(600); // làm chậm dò mật khẩu
            return ApiExceptionHandler.error(401, "Sai tên đăng nhập hoặc mật khẩu");
        }
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return OK;
    }

    /** Tạo tài khoản đầu tiên (chỉ khi chưa có tài khoản nào): chủ workspace 1, đăng nhập luôn */
    @PostMapping("/setup")
    Map<String, Object> setup(@RequestBody(required = false) JsonNode b, HttpServletRequest req, HttpServletResponse res) {
        User u = auth.setup(str(b, "username"), str(b, "name"), str(b, "password"));
        signIn(PasswordAuthProvider.signedIn(u.getUsername()), req, res);
        return OK;
    }

    /** Tự đăng ký (ALLOW_SIGNUP=true): tài khoản + workspace riêng, đăng nhập luôn */
    @PostMapping("/register")
    Map<String, Object> register(@RequestBody(required = false) JsonNode b, HttpServletRequest req, HttpServletResponse res) {
        int wait = attempts.lockedMinutes(req);
        if (wait > 0) throw new com.fbads.common.ApiException(429, "Thử quá nhiều lần. Thử lại sau " + wait + " phút.");
        User u = auth.register(str(b, "username"), str(b, "name"), str(b, "password"), str(b, "workspaceName"));
        signIn(PasswordAuthProvider.signedIn(u.getUsername()), req, res);
        return OK;
    }

    /** Đổi mật khẩu của chính mình, rồi đăng xuất mọi thiết bị khác của người này */
    @PostMapping("/password")
    ResponseEntity<?> password(@Valid @RequestBody Requests.PasswordChange b, HttpServletRequest req, HttpServletResponse res) {
        Long uid = WorkspaceFilter.userId(req);
        if (uid == null) return ApiExceptionHandler.error(400, "Chưa có tài khoản nào. Hãy tạo tài khoản trước.");
        String cur = b.currentPassword() == null ? "" : b.currentPassword();
        if (!cur.isEmpty() && cur.equals(b.newPassword())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Mật khẩu mới phải khác mật khẩu hiện tại");
            body.put("errors", Map.of("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại"));
            return ResponseEntity.badRequest().body(body);
        }
        auth.changePassword(uid, cur, b.newPassword());
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        // Spring Session đánh chỉ mục phiên theo tên người dùng: xoá các phiên khác của người này
        HttpSession mine = req.getSession(false);
        for (String id : sessions.findByPrincipalName(username).keySet()) if (mine == null || !id.equals(mine.getId())) sessions.deleteById(id);
        Long ws = mine == null ? null : (Long) mine.getAttribute(WorkspaceFilter.SESSION_WS);
        signIn(PasswordAuthProvider.signedIn(username), req, res);
        if (ws != null) req.getSession().setAttribute(WorkspaceFilter.SESSION_WS, ws);
        return ResponseEntity.ok(OK);
    }
}
