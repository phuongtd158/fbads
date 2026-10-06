package com.fbads.company;

import com.fbads.common.ApiException;
import com.fbads.common.Fmt;
import com.fbads.common.Ids;
import com.fbads.common.ValidationException;
import com.fbads.dto.AdObject;
import com.fbads.dto.CompanyConfigPatch;
import com.fbads.dto.CompanyReportPatch;
import com.fbads.dto.Metrics;
import com.fbads.engine.EngineClock;
import com.fbads.entity.CompanyConfig;
import com.fbads.entity.CompanyReport;
import com.fbads.event.AppEvent;
import com.fbads.event.EventBus;
import com.fbads.repository.CompanyConfigRepository;
import com.fbads.repository.CompanyReportRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EngineState;
import com.fbads.service.LogService;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookInsights;
import com.fbads.service.facebook.FacebookObjects;
import com.fbads.validation.Result;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Báo cáo lên hệ thống nội bộ của công ty theo các mốc 9h / 12h / 17h / 22h (bản Java của lib/companyReport.js).
 *  - Đến mốc: với mỗi Team đã cấu hình, cộng số Facebook của các chiến dịch thuộc Team thành một bản báo cáo rồi nhắn Telegram.
 *  - Chế độ "Chỉ xem": chỉ tạo bản báo cáo + nhắn Telegram, không bao giờ gửi lên công ty.
 *  - Chế độ "Duyệt trước": chỉ gửi khi người dùng bấm Gửi ở trang Báo cáo công ty.
 *  - Chế độ "Tự động gửi": đến mốc gửi luôn; số bất thường thì dừng cho người dùng xem; lỗi mạng / hệ thống công ty lỗi thì
 *    thử lại tối đa 3 lần, cách nhau 10 phút; sai mật khẩu / bị từ chối thì báo ngay.
 *  - Trước khi gửi luôn hỏi hệ thống công ty mốc đó đã có báo cáo chưa; có rồi thì không gửi đè (12h, 17h, 22h).
 *  - Riêng mốc 9h (chốt cả ngày hôm qua, chạy sáng hôm sau): bản ghi "9h ngày hôm qua" trên công ty đã có thì cập nhật vào đó
 *    (kèm lần sửa + lý do "Chốt số liệu cả ngày dd/mm"), chưa có thì tạo mới, đã khoá thì không đụng tới.
 *  - Báo cáo đã có trên công ty ở các mốc khác: người dùng bấm "Cập nhật lên công ty" (kèm lý do) mới sửa; đã khoá thì không sửa.
 *  - Cài đặt "làm sớm n phút": tạo / gửi báo cáo của mốc lúc (giờ mốc − n phút), báo cáo vẫn ghi đúng mốc.
 * Bản Java không có Telegram hai chiều: tin Telegram không có nút, người dùng xem / sửa / gửi trên trang Báo cáo công ty.
 */
@Service
public class CompanyReportService {
    static final int GRACE_MIN = 10;     // máy bật trễ tối đa 10 phút vẫn tạo báo cáo của mốc
    static final int KEEP = 300;         // giữ tối đa 300 bản báo cáo gần nhất mỗi workspace
    static final int RETRY_MAX = 3;      // lỗi mạng / hệ thống công ty lỗi 5xx: thử tối đa 3 lần
    static final int RETRY_GAP_MIN = 10; // cách nhau 10 phút
    static final int SYNC_DAYS = 3;
    public static final String SOURCE = "Báo cáo công ty";
    public static final String AUTO_SOURCE = "Báo cáo công ty (tự động)";
    static final String LOCKED_MSG = "Báo cáo trên hệ thống công ty đã khoá, tool không sửa được. Muốn sửa hãy gửi yêu "
            + "cầu chỉnh sửa trên web công ty.";
    static final String MANUAL_HINT = "Mở tool → <b>Báo cáo công ty</b> để kiểm tra và gửi.";

    private final CompanyConfigRepository configs;
    private final CompanyReportRepository reports;
    private final CompanyApi api;
    private final FacebookObjects objects;
    private final FacebookInsights insights;
    private final SettingsService settings;
    private final LogService logs;
    private final EventBus events;
    private final EngineClock clock;
    private final EngineState state;
    private final JsonMapper mapper;
    /** Bản báo cáo đang gửi (không gửi hai lần cùng lúc) */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    public CompanyReportService(CompanyConfigRepository configs, CompanyReportRepository reports, CompanyApi api,
            FacebookObjects objects, FacebookInsights insights, SettingsService settings, LogService logs,
            EventBus events, EngineClock clock, EngineState state, JsonMapper mapper) {
        this.configs = configs;
        this.reports = reports;
        this.api = api;
        this.objects = objects;
        this.insights = insights;
        this.settings = settings;
        this.logs = logs;
        this.events = events;
        this.clock = clock;
        this.state = state;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ Cài đặt
    public CompanyConfig config() {
        long ws = WorkspaceContext.require();
        return configs.findById(ws).orElseGet(() -> configs.save(new CompanyConfig(ws)));
    }

    public List<CompanyReport> list() { return reports.findAllByOrderBySeqDesc(); }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", config().publicView());
        m.put("reports", list());
        return m;
    }

    public Map<String, Object> saveConfig(CompanyConfigPatch patch) {
        CompanyConfig c = config();
        Result<CompanyRules.ConfigValue> r = CompanyRules.validateConfig(patch == null ? new CompanyConfigPatch() : patch, c);
        if (!r.ok()) throw new ValidationException(r);
        CompanyRules.ConfigValue v = r.value();
        if (v.enabled() != null) c.setEnabled(v.enabled());
        if (v.mode() != null) c.setMode(v.mode());
        if (v.slots() != null) c.setSlots(new ArrayList<>(v.slots()));
        if (v.leadMin() != null) c.setLeadMin(v.leadMin());
        if (v.baseUrl() != null) c.setBaseUrl(v.baseUrl());
        if (v.email() != null) c.setEmail(v.email());
        if (v.password() != null) c.setPassword(v.password());
        if (v.teams() != null) c.setTeams(new ArrayList<>(v.teams()));
        CompanyConfig saved = configs.save(c);
        api.reset(); // đổi tài khoản/địa chỉ → đăng nhập lại ở lần gọi sau
        return Map.of("config", saved.publicView());
    }

    /** Đăng nhập thử bằng tài khoản đã lưu → tên người dùng + danh sách Team để chọn */
    public Map<String, Object> test() {
        CompanyConfig c = config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", api.test(c));
        m.put("teams", api.listTeams(c));
        return m;
    }

    public Map<String, Object> teams() { return Map.of("teams", api.listTeams(config())); }

    // ------------------------------------------------------------------ Tạo bản báo cáo
    private Instant now() { return Instant.ofEpochMilli(clock.millis()); }

    private boolean isMock() { return settings.get().isMock(); }

    static String teamLabel(CompanyReport r) {
        String s = Stream.of(r.getTeamCode(), r.getTeamName())
                .filter(x -> x != null && !x.isEmpty())
                .collect(Collectors.joining(" · "));
        return s.isEmpty() ? r.getTeamId() : s;
    }

    private static String esc(String t) { return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private void telegram(String text) { events.publish(AppEvent.TELEGRAM_TEXT, "company", true, Map.of("text", text)); }

    private void log(CompanyReport r, String source, String detail, boolean ok, String error, boolean skipped) {
        String mode = settings.get().mode();
        logs.log(l -> {
            l.setKind("company"); l.setSource(source); l.setName(teamLabel(r)); l.setDetail(detail); l.setOk(ok); l.setMode(mode);
            if (error != null) l.setError(Map.of("message", error));
            if (skipped) l.setSkipped(true);
        });
    }

    /** Số Facebook của một Team cho một mốc: 7 số của form + tên các chiến dịch */
    public record Built(Map<String, Long> metrics, List<String> campaigns) {}

    public Built build(CompanyConfig.Team team, int slot) {
        List<AdObject> camps = CompanyRules.teamCampaigns(team, objects.listObjects(false).stream().filter(AdObject::isCampaign).toList());
        Map<String, Metrics> data = insights.rangeMetrics(CompanyRules.rangeOf(slot), false);
        return new Built(CompanyRules.sumMetrics(camps, data), camps.stream().map(o -> o.name).toList());
    }

    /** Tin Telegram của một bản báo cáo */
    String summary(CompanyReport r, String head) {
        Function<String, String> val = k -> r.getMetrics().get(k) == null ? "<i>chưa nhập</i>"
                : "<b>" + Fmt.money(r.getMetrics().get(k)) + "</b>";
        List<String> lines = new ArrayList<>();
        lines.add(head + " <b>Báo cáo công ty · " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()) + "</b>"
                + (CompanyRules.updatesExisting(r.getSlot())
                        ? " · chốt cả ngày, cập nhật sáng " + CompanyRules.dm(CompanyRules.submitDate(r.getSlot(), r.getDate())) : ""));
        lines.add("<b>" + esc(teamLabel(r)) + "</b> (" + (r.getCampaigns() == null ? 0 : r.getCampaigns().size()) + " chiến dịch)");
        lines.add("Chi tiêu Ads: " + val.apply("spend"));
        lines.add("Tin nhắn: " + val.apply("messages") + " · SĐT: " + val.apply("phones"));
        lines.add("Hiển thị: " + val.apply("impressions") + " · Nhấp: " + val.apply("clicks"));
        lines.add("Đơn hàng: " + val.apply("orders") + " · DSO sau VAT: " + val.apply("dso_after"));
        if (!r.getNotes().isEmpty()) lines.add("Ghi chú: " + esc(r.getNotes()));
        return String.join("\n", lines);
    }

    /** Tin báo bản báo cáo vừa tạo (chế độ Chỉ xem / Duyệt trước, hoặc Tự động gửi khi đang Dùng thử) */
    void notifyDraft(CompanyReport r) {
        String foot;
        if (!config().canSend()) foot = "\n\n<i>Chế độ Chỉ xem: tool không gửi báo cáo này lên công ty.</i>";
        else if (isMock()) foot = "\n\n<i>Đang dùng dữ liệu giả (Dùng thử): tool không gửi lên công ty.</i>";
        else foot = "\n\n" + MANUAL_HINT;
        telegram(summary(r, "📋") + foot);
    }

    /**
     * Tạo (hoặc làm mới số Facebook của) bản báo cáo cho mọi Team ở một mốc. Bản đã gửi thì giữ nguyên.
     * Số đã sửa tay và ghi chú được giữ lại khi làm mới. → danh sách bản báo cáo của mốc
     */
    public List<CompanyReport> createDrafts(int slot, String today, boolean silent) {
        CompanyConfig c = config();
        String date = CompanyRules.reportDate(slot, today);
        List<CompanyReport> out = new ArrayList<>();
        for (CompanyConfig.Team team : c.getTeams()) {
            CompanyReport r = reports.findByTeamIdAndDateAndSlot(team.id(), date, slot).orElse(null);
            if (r != null && ("sent".equals(r.getStatus()) || ("exists".equals(r.getStatus()) && !CompanyRules.updatesExisting(slot)))) {
                out.add(r);
                continue;
            }
            Built built;
            try {
                built = build(team, slot);
            } catch (RuntimeException e) {
                String label = !team.code().isEmpty() ? team.code() : !team.name().isEmpty() ? team.name() : team.id();
                String mode = settings.get().mode();
                logs.log(l -> {
                    l.setKind("company");
                    l.setSource(SOURCE);
                    l.setName(label);
                    l.setDetail("Không lấy được số Facebook cho mốc " + slot + "h: " + e.getMessage());
                    l.setOk(false);
                    l.setMode(mode);
                    l.setError(Map.of("message", String.valueOf(e.getMessage())));
                });
                if (!silent || "auto".equals(c.getMode()))
                    telegram("❌ <b>Báo cáo công ty · " + slot + "h ngày " + CompanyRules.dm(date) + "</b>\n<b>" + esc(label)
                            + "</b>: không lấy được số Facebook nên chưa tạo báo cáo.\n" + esc(e.getMessage()));
                continue;
            }
            Instant now = now();
            if (r == null) r = new CompanyReport(Ids.uid(), team.id(), date, slot, now);
            r.setTeamCode(team.code()); r.setTeamName(team.name()); r.setCampaigns(new ArrayList<>(built.campaigns()));
            r.setUpdatedAt(now); r.setBuiltAt(now); r.setStatus("pending"); r.setError("");
            r.setReasons(new ArrayList<>()); r.setAttempts(0); r.setNextTryAt(null);
            Set<String> edited = new HashSet<>(r.getEdited());
            Map<String, Long> metrics = new LinkedHashMap<>(r.getMetrics());
            built.metrics().forEach((k, v) -> { if (!edited.contains(k)) metrics.put(k, v); });
            r.setMetrics(metrics);
            out.add(reports.save(r));
        }
        List<CompanyReport> all = reports.findAllByOrderBySeqDesc();
        if (all.size() > KEEP) reports.deleteAll(all.subList(KEEP, all.size()));
        if (!silent) for (CompanyReport r : out) if ("pending".equals(r.getStatus())) notifyDraft(r);
        return out;
    }

    // ------------------------------------------------------------------ Tự động gửi
    private static boolean retryable(RuntimeException e) { return e instanceof ApiException a && (a.status() == 502 || a.status() == 504); }

    private static String existsFoot(CompanyReport r) {
        return r.getRemote() != null && r.getRemote().locked()
                ? "\nBáo cáo trên công ty đã khoá, muốn sửa hãy gửi yêu cầu chỉnh sửa trên web công ty." : "";
    }

    void attemptAuto(CompanyReport r) {
        r.setAttempts(r.getAttempts() + 1);
        try {
            send(r, AUTO_SOURCE);
            String done = "update".equals(r.getSentAs())
                    ? "Đã tự cập nhật vào báo cáo " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()) + " trên công ty"
                    : "Đã tự gửi lên công ty";
            telegram(summary(r, "✅") + "\n\n" + done + ("LATE".equals(r.getRemoteStatus()) ? " (công ty ghi nhận nộp muộn)" : "") + ".");
        } catch (RuntimeException e) {
            if ("exists".equals(r.getStatus())) {
                String why = CompanyRules.updatesExisting(r.getSlot()) && r.getRemote() != null && r.getRemote().locked()
                        ? "báo cáo của mốc này trên công ty đã khoá nên tool không cập nhật được."
                                : "hệ thống công ty đã có báo cáo của mốc này nên tool không gửi đè.";
                telegram("ℹ️ <b>Báo cáo công ty · " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()) + "</b>\n<b>"
                        + esc(teamLabel(r)) + "</b>: " + why + existsFoot(r));
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
            telegram(summary(r, "❌") + "\n\nKhông tự gửi được" + tries + ": " + esc(e.getMessage()) + "\n" + MANUAL_HINT);
        }
    }

    /** Chế độ Tự động gửi: số bình thường thì gửi luôn; số bất thường thì dừng, nhắn để người dùng xem rồi gửi tay */
    void autoSend(List<CompanyReport> list) {
        for (CompanyReport r : list) {
            if (!"pending".equals(r.getStatus())) continue;
            if (isMock()) { notifyDraft(r); continue; } // Dùng thử: không gửi, chỉ báo
            List<String> reasons = CompanyRules.anomalies(r);
            if (!reasons.isEmpty()) {
                r.setStatus("review");
                r.setReasons(new ArrayList<>(reasons));
                reports.save(r);
                log(r, AUTO_SOURCE, "Chưa tự gửi báo cáo " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()) + ": "
                        + String.join("; ", reasons), false, null, true);
                telegram(summary(r, "⚠️") + "\n\nChưa tự gửi vì số trông bất thường:\n"
                        + String.join("\n", reasons.stream().map(x -> "• " + esc(x)).toList()) + "\n" + MANUAL_HINT);
                continue;
            }
            attemptAuto(r);
        }
    }

    /** Mỗi vòng tự động, trong workspace hiện tại */
    public void tick() {
        CompanyConfig c = config();
        if (!c.isEnabled() || c.getTeams().isEmpty()) return;
        EngineClock.Now now = clock.now();
        boolean auto = "auto".equals(c.getMode());
        for (int slot : c.getSlots()) {
            int at = CompanyRules.fireMinute(slot, c.getLeadMin());
            String mark = "company:" + slot;
            if (now.minutes() < at || now.minutes() - at > GRACE_MIN || state.hasDaily(now.date(), mark)) continue;
            state.putDaily(now.date(), mark, null);
            List<CompanyReport> list = createDrafts(slot, now.date(), auto);
            if (auto) autoSend(list);
        }
        // Bản đang chờ thử lại (lỗi mạng / hệ thống công ty lỗi) đã đến giờ
        if (auto)
            for (CompanyReport r : reports.findByStatus("retry"))
                if (r.getNextTryAt() != null && !r.getNextTryAt().isAfter(now())) attemptAuto(r);
    }

    // ------------------------------------------------------------------ Sửa, gửi, cập nhật
    private CompanyReport find(String id) {
        return reports.findById(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy bản báo cáo này"));
    }

    /** Sửa số / ghi chú. Bản đã khoá trên công ty thì không sửa được. Số đã sửa tay không bị số Facebook ghi đè khi làm mới. */
    public CompanyReport update(String id, CompanyReportPatch patch) {
        CompanyReport r = find(id);
        if (r.isOnRemote() && r.getRemote() != null && r.getRemote().locked()) throw new ApiException(400, LOCKED_MSG);
        Result<CompanyRules.PatchValue> v = CompanyRules.validateReportPatch(patch == null ? CompanyReportPatch.EMPTY : patch);
        if (!v.ok()) throw new ValidationException(v);
        if (!v.value().metrics().isEmpty()) {
            Set<String> edited = new LinkedHashSet<>(r.getEdited());
            Map<String, Long> metrics = new LinkedHashMap<>(r.getMetrics());
            v.value().metrics().forEach((k, val) -> {
                if (!Objects.equals(metrics.get(k), val) || !metrics.containsKey(k)) { metrics.put(k, val); edited.add(k); }
            });
            r.setMetrics(metrics);
            r.setEdited(new ArrayList<>(edited));
        }
        v.value().texts().forEach((k, s) -> {
            switch (k) { case "notes" -> r.setNotes(s); case "issue" -> r.setIssue(s); default -> r.setResolution(s); }
        });
        r.setUpdatedAt(now());
        return reports.save(r);
    }

    /** Báo cáo trên hệ thống công ty → phần tool lưu để hiển thị / so sánh */
    CompanyReport.Remote remoteOf(JsonNode x, Map<String, Long> fallback) {
        Map<String, Long> metrics = new LinkedHashMap<>();
        JsonNode src = x.path("metrics");
        for (String k : CompanyRules.METRIC_KEYS) {
            if (src.isObject()) {
                JsonNode v = src.path(k);
                metrics.put(k, v.isMissingNode() || v.isNull() ? null : Math.round(v.asDouble()));
            }
            else metrics.put(k, fallback == null ? null : fallback.get(k));
        }
        JsonNode rev = x.path("revision");
        Integer revision = rev.isMissingNode() || rev.isNull() ? null : rev.asInt();
        return new CompanyReport.Remote(x.path("id").asString(""), x.path("status").asString(""), revision,
                x.path("locked").asBoolean(false), metrics, x.path("notes").asString(""), x.path("issue").asString(""),
                x.path("resolution").asString(""), x.path("updated_at").asString(""), now().toString());
    }

    private static String what(CompanyReport r) { return "báo cáo " + r.getSlot() + "h ngày " + CompanyRules.dm(r.getDate()); }

    /** Bấm Gửi trên trang Báo cáo công ty */
    public CompanyReport send(String id, String source) { return send(find(id), source); }

    /** Gửi lên hệ thống công ty: người dùng bấm Gửi, hoặc chế độ Tự động gửi. source: nguồn ghi Nhật ký. */
    CompanyReport send(CompanyReport r, String source) {
        CompanyConfig c = config();
        if ("sent".equals(r.getStatus())) throw new ApiException(400, "Báo cáo này đã gửi rồi.");
        if (!c.canSend())
            throw new ApiException(400, "Đang ở chế độ Chỉ xem nên tool không gửi lên công ty. Đổi chế độ gửi ở Cài đặt "
                    + "→ Báo cáo công ty.");
        if (isMock()) throw new ApiException(400, "Tool đang dùng dữ liệu giả (chế độ Dùng thử) nên không gửi báo cáo lên công ty.");
        List<CompanyRules.MetricDef> miss = CompanyRules.missingMetrics(r);
        if (!miss.isEmpty())
            throw new ApiException(400, "Còn thiếu: "
                    + String.join(", ", miss.stream().map(CompanyRules.MetricDef::label).toList()) + ". Nhập đủ rồi mới gửi.");
        if (!inflight.add(r.getId())) throw new ApiException(409, "Báo cáo này đang được gửi.");
        String what = what(r);
        try {
            JsonNode exist = api.findReport(c, r.getTeamId(), r.getDate(), r.getSlot());
            if (exist != null && CompanyRules.updatesExisting(r.getSlot()) && !exist.path("locked").asBoolean(false))
                return closeInto(c, r, exist, what, source);
            if (exist != null) {
                boolean locked = CompanyRules.updatesExisting(r.getSlot());
                r.setStatus("exists"); r.setRemote(remoteOf(exist, null)); r.setRemoteId(exist.path("id").asString(""));
                r.setRemoteStatus(exist.path("status").asString(""));
                r.setError(locked ? LOCKED_MSG : "Hệ thống công ty đã có báo cáo của mốc này (tool không gửi đè). Muốn "
                        + "sửa hãy vào web công ty.");
                if (locked) r.setNextTryAt(null);
                r.setUpdatedAt(now());
                reports.save(r);
                log(r, source, locked ? "Không cập nhật " + what + ": báo cáo trên công ty đã khoá"
                        : "Không gửi " + what + ": công ty đã có báo cáo của mốc này", false, null, false);
                throw new ApiException(409, r.getError());
            }
            Map<String, Object> body = CompanyRules.payloadOf(r);
            JsonNode res = api.submitReport(c, body);
            ObjectNode x = mapper.valueToTree(body);
            x.put("revision", 1);
            if (res != null && res.isObject()) x.setAll((ObjectNode) res);
            x.put("id", res == null ? "" : res.path("id").asString(""));
            r.setRemote(remoteOf(x, r.getMetrics()));
            r.setStatus("sent"); r.setSentAs("create"); r.setSentAt(now());
            r.setRemoteId(res == null ? "" : res.path("id").asString(""));
            r.setRemoteStatus(res == null ? "" : res.path("status").asString(""));
            r.setError(""); r.setReasons(new ArrayList<>()); r.setNextTryAt(null);
            reports.save(r);
            log(r, source, "Đã gửi " + what + " lên công ty"
                    + ("LATE".equals(r.getRemoteStatus()) ? " (công ty ghi nhận nộp muộn)" : ""), true, null, false);
            return r;
        } catch (RuntimeException e) {
            if (!"exists".equals(r.getStatus())) {
                r.setStatus("failed"); r.setError(String.valueOf(e.getMessage())); r.setUpdatedAt(now());
                reports.save(r);
                log(r, source, "Gửi " + what + " thất bại: " + e.getMessage(), false, String.valueOf(e.getMessage()), false);
            }
            throw e;
        } finally {
            inflight.remove(r.getId());
        }
    }

    /** Kết quả lưu sau khi cập nhật bản ghi đã có trên công ty: số vừa gửi + lần sửa tăng 1 + phần công ty trả về */
    private CompanyReport.Remote remoteAfterUpdate(Map<String, Object> body, CompanyReport.Remote fresh, JsonNode res,
            Map<String, Long> metrics) {
        ObjectNode x = mapper.valueToTree(body);
        x.remove("reason");
        x.remove("revision");
        x.put("id", fresh.id());
        x.put("status", fresh.status());
        if (fresh.revision() == null) x.putNull("revision"); else x.put("revision", fresh.revision() + 1);
        if (res != null && res.isObject()) x.setAll((ObjectNode) res);
        return remoteOf(x, metrics);
    }

    /** Mốc 9h: ghi số chốt cả ngày vào bản ghi "9h ngày hôm qua" đã có trên công ty (chưa khoá), kèm lần sửa hiện tại + lý do tự điền */
    private CompanyReport closeInto(CompanyConfig c, CompanyReport r, JsonNode exist, String what, String source) {
        CompanyReport.Remote fresh = remoteOf(exist, null);
        String reason = CompanyRules.closeReason(r.getDate());
        Map<String, Object> body = CompanyRules.updatePayloadOf(r, fresh.revision(), reason);
        JsonNode res = api.submitReport(c, body);
        r.setRemote(remoteAfterUpdate(body, fresh, res, r.getMetrics()));
        Instant now = now();
        r.setStatus("sent"); r.setSentAs("update"); r.setSentAt(now); r.setRemoteUpdatedAt(now);
        r.setRemoteId(r.getRemote().id()); r.setRemoteStatus(r.getRemote().status());
        r.setError(""); r.setReasons(new ArrayList<>()); r.setNextTryAt(null); r.setUpdatedAt(now);
        reports.save(r);
        log(r, source, "Đã cập nhật " + what + " trên công ty (lý do: " + reason + ")", true, null, false);
        return r;
    }

    /**
     * Cập nhật báo cáo đã có trên công ty bằng số đang có trên tool (người dùng bấm, luôn kèm lý do). Không bao giờ tự chạy.
     * Lấy lại báo cáo mới nhất trên công ty: đã khoá → không sửa; lần sửa khác lần tool biết (có người vừa sửa trên
     * web) → tải số mới về, không ghi đè.
     */
    public CompanyReport updateRemote(String id, String reason, String source) {
        CompanyConfig c = config();
        CompanyReport r = find(id);
        if (!r.isOnRemote()) throw new ApiException(400, "Báo cáo này chưa có trên hệ thống công ty, hãy dùng nút Gửi.");
        if (!c.canSend())
            throw new ApiException(400, "Đang ở chế độ Chỉ xem nên tool không gửi gì lên công ty. Đổi chế độ gửi ở Cài "
                    + "đặt → Báo cáo công ty.");
        if (isMock()) throw new ApiException(400, "Tool đang dùng dữ liệu giả (chế độ Dùng thử) nên không cập nhật lên công ty.");
        String bad = CompanyRules.validateReason(reason);
        if (!bad.isEmpty()) throw new ApiException(400, bad);
        List<CompanyRules.MetricDef> miss = CompanyRules.missingMetrics(r);
        if (!miss.isEmpty())
            throw new ApiException(400, "Còn thiếu: " + String.join(", ", miss.stream().map(CompanyRules.MetricDef::label).toList()) + ".");
        if (!inflight.add(r.getId())) throw new ApiException(409, "Báo cáo này đang được gửi.");
        String what = what(r);
        try {
            JsonNode cur = api.findReport(c, r.getTeamId(), r.getDate(), r.getSlot());
            if (cur == null)
                throw new ApiException(409, "Không thấy báo cáo của mốc này trên hệ thống công ty nữa (có thể đã bị "
                        + "xoá). Kiểm tra trên web công ty.");
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
            Map<String, Object> body = CompanyRules.updatePayloadOf(r, fresh.revision(), reason);
            JsonNode res = api.submitReport(c, body);
            r.setRemote(remoteAfterUpdate(body, fresh, res, r.getMetrics()));
            r.setStatus("sent");
            if (r.getSentAs() == null) r.setSentAs("update");
            r.setRemoteId(r.getRemote().id()); r.setRemoteStatus(r.getRemote().status()); r.setError("");
            r.setUpdatedAt(now()); r.setRemoteUpdatedAt(now());
            reports.save(r);
            log(r, source, "Đã cập nhật " + what + " lên công ty (lý do: " + reason.trim() + ")", true, null, false);
            return r;
        } catch (RuntimeException e) {
            log(r, source, "Cập nhật " + what + " thất bại: " + e.getMessage(), false, String.valueOf(e.getMessage()), false);
            throw e;
        } finally {
            inflight.remove(r.getId());
        }
    }

    /**
     * Lấy trạng thái từ công ty (đã nộp / nộp muộn, lần sửa, đã khoá, số đã nộp) cho các bản báo cáo gần đây.
     * Bản chưa gửi mà công ty đã có (nộp tay trên web) → "Công ty đã có" kèm số trên công ty để so. → số bản đã cập nhật
     */
    public int sync() {
        CompanyConfig c = config();
        if (!c.isEnabled() || c.getEmail().isEmpty() || isMock()) return 0;
        String since = now().minusSeconds(SYNC_DAYS * 86_400L).atOffset(ZoneOffset.UTC).toLocalDate().toString();
        Map<String, List<CompanyReport>> byTeam = new LinkedHashMap<>();
        for (CompanyReport r : reports.findByDateGreaterThanEqual(since))
            byTeam.computeIfAbsent(r.getTeamId(), k -> new ArrayList<>()).add(r);
        int n = 0;
        for (Map.Entry<String, List<CompanyReport>> e : byTeam.entrySet()) {
            List<String> dates = e.getValue().stream().map(CompanyReport::getDate).sorted().toList();
            List<JsonNode> remote = api.listReports(c, e.getKey(), dates.getFirst(), dates.getLast());
            for (CompanyReport r : e.getValue()) {
                JsonNode x = remote.stream().filter(y -> r.getDate().equals(y.path("date").asString(""))
                        && y.path("slot").asDouble(-1) == r.getSlot()).findFirst().orElse(null);
                if (x == null) continue;
                r.setRemote(remoteOf(x, null));
                r.setRemoteId(r.getRemote().id());
                r.setRemoteStatus(r.getRemote().status());
                // mốc 9h: bản ghi đã có trên công ty chính là chỗ tool sẽ cập nhật vào, nên vẫn để chờ gửi
                if (!List.of("sent", "exists", "retry").contains(r.getStatus()) && !inflight.contains(r.getId())
                        && !CompanyRules.updatesExisting(r.getSlot())) {
                    r.setStatus("exists");
                    r.setError("Hệ thống công ty đã có báo cáo của mốc này (tool không gửi đè).");
                    r.setNextTryAt(null);
                }
                reports.save(r);
                n++;
            }
        }
        return n;
    }

    public void remove(String id) { reports.delete(find(id)); }

    /** Tạo bản báo cáo của một mốc ngay (không chờ đến giờ) */
    public Map<String, Object> buildNow(Double slotIn, boolean notify) {
        int slot = slotIn == null || slotIn != Math.rint(slotIn) ? -1 : (int) (double) slotIn;
        if (!CompanyRules.SLOTS.contains(slot)) throw new ApiException(400, "Mốc báo cáo không hợp lệ");
        if (config().getTeams().isEmpty()) throw new ApiException(400, "Chưa có Team nào. Thêm Team ở Cài đặt → Báo cáo công ty.");
        List<CompanyReport> list = createDrafts(slot, clock.now().date(), !notify);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reports", list());
        m.put("built", list.stream().map(CompanyReport::getId).toList());
        return m;
    }
}
