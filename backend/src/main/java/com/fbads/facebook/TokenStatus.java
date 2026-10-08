package com.fbads.facebook;

import java.util.List;

/** Token còn dùng được không, hết hạn lúc nào (ms) và còn mấy ngày, quyền đã cấp và quyền còn thiếu */
public record TokenStatus(boolean valid, Long expiresAt, Long daysLeft, List<String> scopes, List<String> missing,
        String type, String appId) {}
