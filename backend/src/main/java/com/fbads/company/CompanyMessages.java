package com.fbads.company;

import com.fbads.common.Fmt;
import com.fbads.entity.CompanyConfig;
import com.fbads.entity.CompanyReport;
import com.fbads.event.EventBus;
import com.fbads.log.LogEntry;
import com.fbads.log.LogError;
import com.fbads.log.LogKind;
import com.fbads.log.LogService;
import com.fbads.notify.Notice;
import com.fbads.service.SettingsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Tin Telegram và dòng Nhật ký của báo cáo công ty (dùng chung cho CompanyDrafts và CompanySender). */
@Component
class CompanyMessages {
    static final String MANUAL_HINT = "Mở tool → <b>Báo cáo công ty</b> để kiểm tra và gửi.";

    private final SettingsService settings;
    private final LogService logs;
    private final EventBus events;

    CompanyMessages(SettingsService settings, LogService logs, EventBus events) {
        this.settings = settings;
        this.logs = logs;
        this.events = events;
    }

    static String teamLabel(CompanyReport r) {
        String s = Stream.of(r.getTeamCode(), r.getTeamName())
                .filter(x -> x != null && !x.isEmpty())
                .collect(Collectors.joining(" · "));
        return s.isEmpty() ? r.getTeamId() : s;
    }

    /** Thoát ký tự HTML cho thông báo (Notice viết bằng HTML rút gọn) */
    static String esc(String t) {
        return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    void notify(String text) {
        events.notify("company", new Notice(Notice.Topic.COMPANY, text));
    }

    /** Ghi Nhật ký với tên = Team của bản báo cáo */
    void log(CompanyReport r, String source, String detail, boolean ok, String error, boolean skipped) {
        log(teamLabel(r), source, detail, ok, error, skipped);
    }

    void log(String name, String source, String detail, boolean ok, String error, boolean skipped) {
        LogEntry l = LogEntry.of(LogKind.COMPANY, source, name);
        l.setDetail(detail);
        l.setOk(ok);
        l.setMode(settings.get().mode());
        if (error != null) l.setError(LogError.of(error));
        if (skipped) l.setSkipped(true);
        logs.add(l);
    }

    /** Một số liệu của báo cáo để hiện trong tin: in đậm, hoặc "chưa nhập" */
    private static String metric(CompanyReport r, String key) {
        Long v = r.getMetrics().get(key);
        return v == null ? "<i>chưa nhập</i>" : "<b>" + Fmt.money(v) + "</b>";
    }

    /** Tin Telegram của một bản báo cáo */
    String summary(CompanyReport r, String head) {
        List<String> lines = new ArrayList<>();
        lines.add(head + " <b>Báo cáo công ty · " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()) + "</b>"
                + (CompanyRules.updatesExisting(r.getSlot())
                        ? " · chốt cả ngày, cập nhật sáng "
                                + CompanyRules.dm(CompanyRules.submitDate(r.getSlot(), r.getDate()))
                        : ""));
        lines.add("<b>" + esc(teamLabel(r)) + "</b> (" + (r.getCampaigns() == null ? 0 : r.getCampaigns().size())
                + " chiến dịch)");
        lines.add("Chi tiêu Ads: " + metric(r, "spend"));
        lines.add("Tin nhắn: " + metric(r, "messages") + " · SĐT: " + metric(r, "phones"));
        lines.add("Hiển thị: " + metric(r, "impressions") + " · Nhấp: " + metric(r, "clicks"));
        lines.add("Đơn hàng: " + metric(r, "orders") + " · DSO sau VAT: " + metric(r, "dso_after"));
        if (!r.getNotes().isEmpty()) lines.add("Ghi chú: " + esc(r.getNotes()));
        return String.join("\n", lines);
    }

    /** Tin báo bản báo cáo vừa tạo (chế độ Chỉ xem / Duyệt trước, hoặc Tự động gửi khi đang Dùng thử) */
    void notifyDraft(CompanyConfig c, CompanyReport r) {
        String foot;
        if (!c.canSend()) foot = "\n\n<i>Chế độ Chỉ xem: tool không gửi báo cáo này lên công ty.</i>";
        else if (settings.get().isMock()) foot = "\n\n<i>Đang dùng dữ liệu giả (Dùng thử): tool không gửi lên công ty.</i>";
        else foot = "\n\n" + MANUAL_HINT;
        notify(summary(r, "📋") + foot);
    }
}
