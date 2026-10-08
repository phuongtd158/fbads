package com.fbads.facebook;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Trang Kết nối: ok, người dùng, từng tài khoản, tên/tiền tệ/trạng thái gộp, token. Chế độ dùng thử chỉ có
 * ok/mock/name/currency; lỗi chỉ có ok = false và error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Connection(boolean ok, Boolean mock, String user, List<FbAccount> accounts, String name, String currency,
        String status, Boolean accountActive, TokenStatus token, Long checkedAt, String error) {
    public static Connection demo() {
        return new Connection(true, true, null, null, "Chế độ dùng thử (dữ liệu giả)", "VND", null, null, null, null, null);
    }

    public static Connection failed(String error) {
        return new Connection(false, null, null, null, null, null, null, null, null, null, error);
    }
}
