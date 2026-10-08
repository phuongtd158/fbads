package com.fbads.facebook;

import java.util.List;

/** POST /api/fb/accounts: tên người dùng, tình trạng token, các tài khoản quảng cáo của token */
public record FbAccounts(String user, TokenStatus token, List<FbAccount> accounts) {}
