package com.fbads.company;

import java.util.List;

/** Tạo báo cáo ngay: reports = mọi bản báo cáo, built = id các bản vừa tạo / làm mới */
public record CompanyBuilt(List<CompanyReport> reports, List<String> built) {}
