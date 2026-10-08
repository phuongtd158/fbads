package com.fbads.company;

/** POST /api/company/reports/{id}/update: cập nhật báo cáo đã có trên công ty, lý do bắt buộc */
public record CompanyUpdate(String reason) {
    public static final CompanyUpdate EMPTY = new CompanyUpdate(null);
}
