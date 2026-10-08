package com.fbads.company;

import com.fbads.common.ApiException;
import com.fbads.company.CompanyJson.NewReport;
import com.fbads.company.CompanyJson.RemoteReport;
import com.fbads.company.CompanyJson.ReportUpdate;
import com.fbads.engine.EngineClock;
import com.fbads.entity.CompanyConfig;
import com.fbads.entity.CompanyReport;
import com.fbads.repository.CompanyReportRepository;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.fbads.company.CompanyMessages.MANUAL_HINT;
import static com.fbads.company.CompanyMessages.esc;
import static com.fbads.company.CompanyMessages.teamLabel;

/**
 * Gửi lên hệ thống công ty: gửi bản mới, cập nhật bản đã có (mốc 9h tự cập nhật; mốc khác chỉ khi người dùng bấm),
 * và chế độ Tự động gửi kèm thử lại khi lỗi mạng.
 */
@Component
class CompanySender {
    static final int RETRY_MAX = 3;      // lỗi mạng / hệ thống công ty lỗi 5xx: thử tối đa 3 lần
    static final int RETRY_GAP_MIN = 10; // cách nhau 10 phút
    static final String LOCKED_MSG = "Báo cáo trên hệ thống công ty đã khoá, tool không sửa được. Muốn sửa hãy gửi yêu "
            + "cầu chỉnh sửa trên web công ty.";

    private final CompanyReportRepository reports;
    private final CompanyApi api;
    private final CompanyMessages messages;
    private final SettingsService settings;
    private final EngineClock clock;
    /** Bản báo cáo đang gửi (không gửi hai lần cùng lúc) */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    CompanySender(CompanyReportRepository reports, CompanyApi api, CompanyMessages messages, SettingsService settings,
            EngineClock clock) {
        this.reports = reports;
        this.api = api;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private boolean isMock() {
        return settings.get().isMock();
    }

    /** true = bản báo cáo này đang được gửi */
    boolean isSending(String id) {
        return inflight.contains(id);
    }

    private static String what(CompanyReport r) {
        return "báo cáo " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate());
    }

    // ------------------------------------------------------------------ Gửi

    /** Gửi lên hệ thống công ty: người dùng bấm Gửi, hoặc chế độ Tự động gửi. source: nguồn ghi Nhật ký. */
    CompanyReport send(CompanyConfig c, CompanyReport r, String source) {
        if ("sent".equals(r.getStatus())) throw new ApiException(400, "Báo cáo này đã gửi rồi.");
        if (!c.canSend()) {
            throw new ApiException(400, "Đang ở chế độ Chỉ xem nên tool không gửi lên công ty. Đổi chế độ gửi ở Cài đặt "
                    + "→ Báo cáo công ty.");
        }
        if (isMock()) {
            throw new ApiException(400, "Tool đang dùng dữ liệu giả (chế độ Dùng thử) nên không gửi báo cáo lên công ty.");
        }
        List<CompanyRules.MetricDef> miss = CompanyRules.missingMetrics(r);
        if (!miss.isEmpty()) {
            throw new ApiException(400, "Còn thiếu: "
                    + String.join(", ", miss.stream().map(CompanyRules.MetricDef::label).toList()) + ". Nhập đủ rồi mới gửi.");
        }
        if (!inflight.add(r.getId())) throw new ApiException(409, "Báo cáo này đang được gửi.");
        String what = what(r);
        try {
            RemoteReport exist = api.findReport(c, r.getTeamId(), r.getDate(), r.getSlot());
            if (exist != null && CompanyRules.updatesExisting(r.getSlot()) && !exist.isLocked()) {
                return closeInto(c, r, exist, what, source);
            }
            if (exist != null) {
                markExists(r, exist, what, source);
                throw new ApiException(409, r.getError());
            }
            NewReport body = CompanyRules.payloadOf(r);
            RemoteReport res = api.submitReport(c, body);
            // bản vừa gửi (lần sửa 1), phần công ty trả về đè lên
            RemoteReport sent = asSent("", "", 1, body.metrics(), body.notes(), body.issue(), body.resolution()).overlay(res);
            r.setRemote(remoteOf(sent, r.getMetrics()));
            r.setStatus("sent");
            r.setSentAs("create");
            r.setSentAt(now());
            r.setRemoteId(res == null ? "" : res.id());
            r.setRemoteStatus(res == null ? "" : res.status());
            r.setError("");
            r.setReasons(new ArrayList<>());
            r.setNextTryAt(null);
            reports.save(r);
            messages.log(r, source, "Đã gửi " + what + " lên công ty"
                    + ("LATE".equals(r.getRemoteStatus()) ? " (công ty ghi nhận nộp muộn)" : ""), true, null, false);
            return r;
        } catch (RuntimeException e) {
            if (!"exists".equals(r.getStatus())) {
                r.setStatus("failed");
                r.setError(String.valueOf(e.getMessage()));
                r.setUpdatedAt(now());
                reports.save(r);
                messages.log(r, source, "Gửi " + what + " thất bại: " + e.getMessage(), false,
                        String.valueOf(e.getMessage()), false);
            }
            throw e;
        } finally {
            inflight.remove(r.getId());
        }
    }

    /** Công ty đã có báo cáo của mốc này (hoặc đã khoá, với mốc 9h): không gửi đè, ghi lại số trên công ty để so */
    private void markExists(CompanyReport r, RemoteReport exist, String what, String source) {
        boolean locked = CompanyRules.updatesExisting(r.getSlot());
        r.setStatus("exists");
        r.setRemote(remoteOf(exist, null));
        r.setRemoteId(exist.id());
        r.setRemoteStatus(exist.status());
        r.setError(locked ? LOCKED_MSG : "Hệ thống công ty đã có báo cáo của mốc này (tool không gửi đè). Muốn "
                + "sửa hãy vào web công ty.");
        if (locked) r.setNextTryAt(null);
        r.setUpdatedAt(now());
        reports.save(r);
        messages.log(r, source, locked ? "Không cập nhật " + what + ": báo cáo trên công ty đã khoá"
                : "Không gửi " + what + ": công ty đã có báo cáo của mốc này", false, null, false);
    }

    /**
     * Mốc 9h: ghi số chốt cả ngày vào bản ghi "9h ngày hôm qua" đã có trên công ty (chưa khoá), kèm lần sửa hiện tại
     * + lý do tự điền
     */
    private CompanyReport closeInto(CompanyConfig c, CompanyReport r, RemoteReport exist, String what, String source) {
        CompanyReport.Remote fresh = remoteOf(exist, null);
        String reason = CompanyRules.closeReason(r.getDate());
        ReportUpdate body = CompanyRules.updatePayloadOf(r, fresh.revision(), reason);
        RemoteReport res = api.submitReport(c, body);
        r.setRemote(remoteAfterUpdate(body, fresh, res, r.getMetrics()));
        Instant now = now();
        r.setStatus("sent");
        r.setSentAs("update");
        r.setSentAt(now);
        r.setRemoteUpdatedAt(now);
        r.setRemoteId(r.getRemote().id());
        r.setRemoteStatus(r.getRemote().status());
        r.setError("");
        r.setReasons(new ArrayList<>());
        r.setNextTryAt(null);
        r.setUpdatedAt(now);
        reports.save(r);
        messages.log(r, source, "Đã cập nhật " + what + " trên công ty (lý do: " + reason + ")", true, null, false);
        return r;
    }

    // ------------------------------------------------------------------ Cập nhật bản đã có (người dùng bấm)

    /**
     * Cập nhật báo cáo đã có trên công ty bằng số đang có trên tool (người dùng bấm, luôn kèm lý do). Không bao giờ
     * tự chạy. Lấy lại báo cáo mới nhất trên công ty: đã khoá → không sửa; lần sửa khác lần tool biết (có người vừa
     * sửa trên web) → tải số mới về, không ghi đè.
     */
    CompanyReport updateRemote(CompanyConfig c, CompanyReport r, String reason, String source) {
        if (!r.isOnRemote()) throw new ApiException(400, "Báo cáo này chưa có trên hệ thống công ty, hãy dùng nút Gửi.");
        if (!c.canSend()) {
            throw new ApiException(400, "Đang ở chế độ Chỉ xem nên tool không gửi gì lên công ty. Đổi chế độ gửi ở Cài "
                    + "đặt → Báo cáo công ty.");
        }
        if (isMock()) {
            throw new ApiException(400, "Tool đang dùng dữ liệu giả (chế độ Dùng thử) nên không cập nhật lên công ty.");
        }
        String bad = CompanyRules.validateReason(reason);
        if (!bad.isEmpty()) throw new ApiException(400, bad);
        List<CompanyRules.MetricDef> miss = CompanyRules.missingMetrics(r);
        if (!miss.isEmpty()) {
            throw new ApiException(400, "Còn thiếu: "
                    + String.join(", ", miss.stream().map(CompanyRules.MetricDef::label).toList()) + ".");
        }
        if (!inflight.add(r.getId())) throw new ApiException(409, "Báo cáo này đang được gửi.");
        String what = what(r);
        try {
            RemoteReport cur = api.findReport(c, r.getTeamId(), r.getDate(), r.getSlot());
            if (cur == null) {
                throw new ApiException(409, "Không thấy báo cáo của mốc này trên hệ thống công ty nữa (có thể đã bị "
                        + "xoá). Kiểm tra trên web công ty.");
            }
            Integer known = r.getRemote() == null ? null : r.getRemote().revision();
            CompanyReport.Remote fresh = remoteOf(cur, null);
            if (fresh.locked()) {
                r.setRemote(fresh);
                reports.save(r);
                throw new ApiException(400, LOCKED_MSG);
            }
            if (known != null && fresh.revision() != null && !fresh.revision().equals(known)) {
                r.setRemote(fresh);
                reports.save(r);
                throw new ApiException(409, "Báo cáo trên công ty vừa được sửa ở nơi khác (lần sửa " + fresh.revision()
                        + "). Tool đã tải số mới về, kiểm tra lại rồi bấm cập nhật lần nữa.");
            }
            ReportUpdate body = CompanyRules.updatePayloadOf(r, fresh.revision(), reason);
            RemoteReport res = api.submitReport(c, body);
            r.setRemote(remoteAfterUpdate(body, fresh, res, r.getMetrics()));
            r.setStatus("sent");
            if (r.getSentAs() == null) r.setSentAs("update");
            r.setRemoteId(r.getRemote().id());
            r.setRemoteStatus(r.getRemote().status());
            r.setError("");
            r.setUpdatedAt(now());
            r.setRemoteUpdatedAt(now());
            reports.save(r);
            messages.log(r, source, "Đã cập nhật " + what + " lên công ty (lý do: " + reason.trim() + ")", true, null,
                    false);
            return r;
        } catch (RuntimeException e) {
            messages.log(r, source, "Cập nhật " + what + " thất bại: " + e.getMessage(), false,
                    String.valueOf(e.getMessage()), false);
            throw e;
        } finally {
            inflight.remove(r.getId());
        }
    }

    /**
     * Báo cáo trên hệ thống công ty → phần tool lưu để hiển thị / so sánh. Công ty không gửi kèm số liệu thì dùng
     * fallback (null = để trống).
     */
    CompanyReport.Remote remoteOf(RemoteReport x, Map<String, Long> fallback) {
        Map<String, Long> metrics = new LinkedHashMap<>();
        for (String k : CompanyRules.METRIC_KEYS) {
            if (x.metrics() != null) {
                Double v = x.metrics().get(k);
                metrics.put(k, v == null ? null : Math.round(v));
            } else {
                metrics.put(k, fallback == null ? null : fallback.get(k));
            }
        }
        return new CompanyReport.Remote(x.id(), x.status(), x.revision(), x.isLocked(), metrics, x.notes(), x.issue(),
                x.resolution(), x.updatedAt(), now().toString());
    }

    /** Kết quả lưu sau khi cập nhật bản ghi đã có trên công ty: số vừa gửi + lần sửa tăng 1 + phần công ty trả về */
    private CompanyReport.Remote remoteAfterUpdate(ReportUpdate body, CompanyReport.Remote fresh, RemoteReport res,
            Map<String, Long> metrics) {
        Integer revision = fresh.revision() == null ? null : fresh.revision() + 1;
        RemoteReport sent = asSent(fresh.id(), fresh.status(), revision, body.metrics(), body.notes(), body.issue(),
                body.resolution());
        return remoteOf(sent.overlay(res), metrics);
    }

    /** Báo cáo như tool vừa gửi lên (chưa có phần công ty trả về) */
    private static RemoteReport asSent(String id, String status, Integer revision, Map<String, Long> metrics, String notes,
            String issue, String resolution) {
        Map<String, Double> m = new LinkedHashMap<>();
        metrics.forEach((k, v) -> m.put(k, v == null ? null : v.doubleValue()));
        return new RemoteReport(id, null, null, null, null, status, revision, null, m, notes, issue, resolution, null);
    }

    // ------------------------------------------------------------------ Tự động gửi

    /** Chế độ Tự động gửi: số bình thường thì gửi luôn; số bất thường thì dừng, nhắn để người dùng xem rồi gửi tay */
    void autoSend(CompanyConfig c, List<CompanyReport> list) {
        for (CompanyReport r : list) {
            if (!"pending".equals(r.getStatus())) continue;
            if (isMock()) { // Dùng thử: không gửi, chỉ báo
                messages.notifyDraft(c, r);
                continue;
            }
            List<String> reasons = CompanyRules.anomalies(r);
            if (!reasons.isEmpty()) {
                r.setStatus("review");
                r.setReasons(new ArrayList<>(reasons));
                reports.save(r);
                messages.log(r, CompanyReportService.AUTO_SOURCE, "Chưa tự gửi báo cáo " + r.getSlot() + "h ngày "
                        + CompanyRules.dm(r.getDate()) + ": " + String.join("; ", reasons), false, null, true);
                messages.notify(messages.summary(r, "⚠️") + "\n\nChưa tự gửi vì số trông bất thường:\n"
                        + String.join("\n", reasons.stream().map(x -> "• " + esc(x)).toList()) + "\n" + MANUAL_HINT);
                continue;
            }
            attemptAuto(c, r);
        }
    }

    /** Bản đang chờ thử lại (lỗi mạng / hệ thống công ty lỗi) đã đến giờ thì gửi lại */
    void retryDue(CompanyConfig c) {
        for (CompanyReport r : reports.findByStatus("retry")) {
            if (r.getNextTryAt() != null && !r.getNextTryAt().isAfter(now())) attemptAuto(c, r);
        }
    }

    private void attemptAuto(CompanyConfig c, CompanyReport r) {
        r.setAttempts(r.getAttempts() + 1);
        try {
            send(c, r, CompanyReportService.AUTO_SOURCE);
            String done = "update".equals(r.getSentAs())
                    ? "Đã tự cập nhật vào báo cáo " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()) + " trên công ty"
                    : "Đã tự gửi lên công ty";
            messages.notify(messages.summary(r, "✅") + "\n\n" + done
                    + ("LATE".equals(r.getRemoteStatus()) ? " (công ty ghi nhận nộp muộn)" : "") + ".");
        } catch (RuntimeException e) {
            if ("exists".equals(r.getStatus())) {
                String why = CompanyRules.updatesExisting(r.getSlot()) && r.getRemote() != null && r.getRemote().locked()
                        ? "báo cáo của mốc này trên công ty đã khoá nên tool không cập nhật được."
                        : "hệ thống công ty đã có báo cáo của mốc này nên tool không gửi đè.";
                messages.notify("ℹ️ <b>Báo cáo công ty · " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate())
                        + "</b>\n<b>" + esc(teamLabel(r)) + "</b>: " + why + existsFoot(r));
                return;
            }
            if (retryable(e) && r.getAttempts() < RETRY_MAX) {
                r.setStatus("retry");
                r.setNextTryAt(now().plusSeconds(RETRY_GAP_MIN * 60L));
                reports.save(r);
                return;
            }
            r.setStatus("failed");
            r.setNextTryAt(null);
            reports.save(r);
            String tries = r.getAttempts() > 1 ? " (đã thử " + r.getAttempts() + " lần)" : "";
            messages.notify(messages.summary(r, "❌") + "\n\nKhông tự gửi được" + tries + ": " + esc(e.getMessage())
                    + "\n" + MANUAL_HINT);
        }
    }

    private static boolean retryable(RuntimeException e) {
        return e instanceof ApiException a && (a.status() == 502 || a.status() == 504);
    }

    private static String existsFoot(CompanyReport r) {
        return r.getRemote() != null && r.getRemote().locked()
                ? "\nBáo cáo trên công ty đã khoá, muốn sửa hãy gửi yêu cầu chỉnh sửa trên web công ty." : "";
    }
}
