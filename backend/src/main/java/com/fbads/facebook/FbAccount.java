package com.fbads.facebook;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Một tài khoản quảng cáo; tài khoản lỗi thì có error (và không có currency) */
public record FbAccount(String id, String name, @JsonInclude(JsonInclude.Include.NON_NULL) String currency, String status,
        boolean active, @JsonInclude(JsonInclude.Include.NON_NULL) String error) {}
