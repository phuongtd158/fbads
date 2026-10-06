package com.fbads.company;

import com.fbads.common.Ids;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.engine.EngineClock;
import com.fbads.entity.CompanyConfig;
import com.fbads.entity.CompanyReport;
import com.fbads.repository.CompanyReportRepository;
import com.fbads.service.facebook.FacebookInsights;
import com.fbads.service.facebook.FacebookObjects;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.fbads.company.CompanyMessages.esc;

/** Tạo bản báo cáo (bản nháp) của một mốc từ số Facebook, hoặc làm mới số của bản chưa gửi. Không gửi gì lên công ty. */
@Component
class CompanyDrafts {
    static final int KEEP = 300; // giữ tối đa 300 bản báo cáo gần nhất mỗi workspace

    /** Số Facebook của một Team cho một mốc: 7 số của form + tên các chiến dịch */
    record Built(Map<String, Long> metrics, List<String> campaigns) {}

    private final CompanyReportRepository reports;
    private final FacebookObjects objects;
    private final FacebookInsights insights;
    private final CompanyMessages messages;
    private final EngineClock clock;

    CompanyDrafts(CompanyReportRepository reports, FacebookObjects objects, FacebookInsights insights,
            CompanyMessages messages, EngineClock clock) {
        this.reports = reports;
        this.objects = objects;
        this.insights = insights;
        this.messages = messages;
        this.clock = clock;
    }

    Built build(CompanyConfig.Team team, int slot) {
        List<AdObject> camps = CompanyRules.teamCampaigns(team,
                objects.listObjects(false).stream().filter(AdObject::isCampaign).toList());
        Map<String, Metrics> data = insights.rangeMetrics(CompanyRules.rangeOf(slot), false);
        return new Built(CompanyRules.sumMetrics(camps, data), camps.stream().map(o -> o.name).toList());
    }

    /**
     * Tạo (hoặc làm mới số Facebook của) bản báo cáo cho mọi Team ở một mốc. Bản đã gửi thì giữ nguyên.
     * Số đã sửa tay và ghi chú được giữ lại khi làm mới. → danh sách bản báo cáo của mốc
     */
    List<CompanyReport> createDrafts(CompanyConfig c, int slot, String today, boolean silent) {
        String date = CompanyRules.reportDate(slot, today);
        List<CompanyReport> out = new ArrayList<>();
        for (CompanyConfig.Team team : c.getTeams()) {
            CompanyReport r = reports.findByTeamIdAndDateAndSlot(team.id(), date, slot).orElse(null);
            if (r != null && ("sent".equals(r.getStatus())
                    || ("exists".equals(r.getStatus()) && !CompanyRules.updatesExisting(slot)))) {
                out.add(r);
                continue;
            }
            Built built;
            try {
                built = build(team, slot);
            } catch (RuntimeException e) {
                String label = !team.code().isEmpty() ? team.code() : !team.name().isEmpty() ? team.name() : team.id();
                messages.log(label, CompanyReportService.SOURCE,
                        "Không lấy được số Facebook cho mốc " + slot + "h: " + e.getMessage(), false,
                        String.valueOf(e.getMessage()), false);
                if (!silent || "auto".equals(c.getMode())) {
                    messages.telegram("❌ <b>Báo cáo công ty · " + slot + "h ngày " + CompanyRules.dm(date) + "</b>\n<b>"
                            + esc(label) + "</b>: không lấy được số Facebook nên chưa tạo báo cáo.\n"
                            + esc(e.getMessage()));
                }
                continue;
            }
            out.add(reports.save(fill(r, team, date, slot, built)));
        }
        List<CompanyReport> all = reports.findAllByOrderBySeqDesc();
        if (all.size() > KEEP) reports.deleteAll(all.subList(KEEP, all.size()));
        if (!silent) {
            for (CompanyReport r : out) {
                if ("pending".equals(r.getStatus())) messages.notifyDraft(c, r);
            }
        }
        return out;
    }

    /** Ghi số mới vào bản báo cáo (tạo mới nếu chưa có), giữ các số người dùng đã sửa tay */
    private CompanyReport fill(CompanyReport r, CompanyConfig.Team team, String date, int slot, Built built) {
        Instant now = Instant.ofEpochMilli(clock.millis());
        if (r == null) r = new CompanyReport(Ids.uid(), team.id(), date, slot, now);
        r.setTeamCode(team.code());
        r.setTeamName(team.name());
        r.setCampaigns(new ArrayList<>(built.campaigns()));
        r.setUpdatedAt(now);
        r.setBuiltAt(now);
        r.setStatus("pending");
        r.setError("");
        r.setReasons(new ArrayList<>());
        r.setAttempts(0);
        r.setNextTryAt(null);
        Set<String> edited = new HashSet<>(r.getEdited());
        Map<String, Long> metrics = new LinkedHashMap<>(r.getMetrics());
        built.metrics().forEach((k, v) -> {
            if (!edited.contains(k)) metrics.put(k, v);
        });
        r.setMetrics(metrics);
        return r;
    }
}
