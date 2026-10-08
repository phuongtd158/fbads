package com.fbads.company;

import java.util.List;
import java.util.Map;

/** config = cài đặt đã bỏ mật khẩu (CompanyConfig.publicView) */
public record CompanyOverview(Map<String, Object> config, List<CompanyReport> reports) {}
