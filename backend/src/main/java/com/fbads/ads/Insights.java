package com.fbads.ads;

import com.fbads.ads.ObjectsMeta.AccountError;
import com.fbads.ads.ObjectsMeta.Usage;

import java.util.List;
import java.util.Map;

/** GET /api/insights: số liệu theo khoảng ngày, metrics = { [id camp/nhóm QC]: số liệu } */
public record Insights(Map<String, Object> range, String key, String since, String until, Integer days, Long at,
        boolean stale, Long blockedUntil, Usage usage, List<AccountError> accountErrors,
        Map<String, Metrics> metrics) {}
