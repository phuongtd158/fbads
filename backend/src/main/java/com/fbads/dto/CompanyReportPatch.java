package com.fbads.dto;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Body của POST /api/company/reports/{id}: số muốn sửa (trống/null = chưa nhập, khoá không gửi = giữ nguyên) và 3 ô chữ.
 * metrics đọc thành Object để kiểm tra như Number() của JS ("12" = 12, "abc" báo lỗi đúng ô). Luật ở CompanyRules.validateReportPatch.
 */
public final class CompanyReportPatch {
    public static final CompanyReportPatch EMPTY = new CompanyReportPatch();

    private final Set<String> sent = new LinkedHashSet<>();
    private Map<String, Object> metrics;
    private String notes;
    private String issue;
    private String resolution;

    public boolean has(String key) { return sent.contains(key); }

    public String text(String key) {
        return switch (key) { case "notes" -> notes; case "issue" -> issue; case "resolution" -> resolution; default -> null; };
    }

    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> v) { metrics = v; sent.add("metrics"); }
    public String getNotes() { return notes; }
    public void setNotes(String v) { notes = v; sent.add("notes"); }
    public String getIssue() { return issue; }
    public void setIssue(String v) { issue = v; sent.add("issue"); }
    public String getResolution() { return resolution; }
    public void setResolution(String v) { resolution = v; sent.add("resolution"); }
}
