package com.fbads.security;

import com.fbads.service.AuthService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Component;

/**
 * AuthenticationProvider của Spring Security: đúng tên đăng nhập + mật khẩu → Authentication có tên = tên đăng nhập.
 * Quyền trong từng workspace (OWNER / EDITOR / VIEWER) không nằm ở đây mà do WorkspaceFilter tra theo workspace đang chọn.
 */
@Component
public class PasswordAuthProvider implements AuthenticationProvider {
    private final AuthService auth;

    public PasswordAuthProvider(AuthService auth) { this.auth = auth; }

    @Override
    public Authentication authenticate(Authentication a) {
        String pw = a.getCredentials() == null ? "" : a.getCredentials().toString();
        return auth.authenticate(a.getName(), pw)
                .map(u -> signedIn(u.getUsername()))
                .orElseThrow(() -> new BadCredentialsException("Sai tên đăng nhập hoặc mật khẩu"));
    }

    public static Authentication signedIn(String username) {
        return UsernamePasswordAuthenticationToken.authenticated(username, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    @Override
    public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
