package com.fbads.ads;

import java.util.List;

/**
 * Thông tin kèm danh sách camp: số liệu lúc nào (at), có phải số cũ không (stale), bị chặn tới khi nào
 * (blockedUntil), mức dùng API, tài khoản và lỗi từng tài khoản
 */
public record ObjectsMeta(Long at, boolean stale, Long blockedUntil, Usage usage,
        List<AccountRef> accounts, List<AccountError> accountErrors) {
    /** Mức dùng API Facebook: phần trăm và loại giới hạn */
    public record Usage(double pct, String tier) {}

    /** Một tài khoản quảng cáo đang quản lý */
    public record AccountRef(String id, String name, String currency) {}

    /** Tài khoản tải lỗi (các tài khoản khác vẫn hiện) */
    public record AccountError(String id, String name, String error) {}
}
