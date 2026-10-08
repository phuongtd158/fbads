package com.fbads.account;

import com.fbads.common.Json;

/** Tạo hoặc đổi tên workspace */
public record WorkspaceName(String name) {
    public static final WorkspaceName EMPTY = new WorkspaceName(null);

    public WorkspaceName { name = Json.str(name); }
}
