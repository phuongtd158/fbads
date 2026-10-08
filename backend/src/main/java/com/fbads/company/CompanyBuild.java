package com.fbads.company;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fbads.common.JsNumber;

/** POST /api/company/reports/build: tạo bản báo cáo của một mốc ngay; notify = nhắn Telegram như đến mốc */
public record CompanyBuild(@JsNumber Double slot,
        @JsonProperty("notify") Boolean telegram) {
    public static final CompanyBuild EMPTY = new CompanyBuild(null, null);
}
