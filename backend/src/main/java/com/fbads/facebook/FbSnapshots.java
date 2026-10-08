package com.fbads.facebook;

import com.fbads.ads.AdObject;
import com.fbads.ads.Metrics;
import com.fbads.ads.ObjectsMeta;

import java.util.List;
import java.util.Map;

/** Dữ liệu Facebook lưu ở Redis (Spring Cache) để khởi động lại không phải tải lại ngay và vẫn có số cũ khi bị Facebook chặn. */
public final class FbSnapshots {
    private FbSnapshots() {}

    /** Danh sách camp/nhóm QC lúc `at` (mili giây) */
    public record Objects(long at, List<AdObject> items, List<ObjectsMeta.AccountError> accountErrors, String currency) {}

    /** Số liệu theo khoảng ngày: { [id]: metrics } lúc `at` */
    public record Range(long at, Map<String, Metrics> data, boolean mock) {}
}
