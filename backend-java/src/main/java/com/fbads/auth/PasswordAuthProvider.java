package com.fbads.auth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Component;

/** AuthenticationProvider của Spring Security: đúng mật khẩu → người dùng "admin" (tool chỉ có 1 người dùng). */
@Component
public class PasswordAuthProvider implements AuthenticationProvider {
    private final AuthService auth;

    public PasswordAuthProvider(AuthService auth) { this.auth = auth; }

    @Override
    public Authentication authenticate(Authentication a) {
        String pw = a.getCredentials() == null ? "" : a.getCredentials().toString();
        if (!auth.verify(pw)) throw new BadCredentialsException("Sai mật khẩu");
        return UsernamePasswordAuthenticationToken.authenticated(AuthService.USER, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }

    @Override
    public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
