package com.fbads.company;

import com.fbads.common.ApiException;
import com.fbads.common.ValidationException;
import com.fbads.company.CompanyJson.RemoteReport;
import com.fbads.dto.CompanyConfigPatch;
import com.fbads.dto.CompanyReportPatch;
import com.fbads.dto.Responses.CompanyBuilt;
import com.fbads.dto.Responses.CompanyConfigSaved;
import com.fbads.dto.Responses.CompanyLogin;
import com.fbads.dto.Responses.CompanyOverview;
import com.fbads.dto.Responses.CompanyTeams;
import com.fbads.engine.EngineClock;
import com.fbads.entity.CompanyConfig;
import com.fbads.entity.CompanyReport;
import com.fbads.repository.CompanyConfigRepository;
import com.fbads.repository.CompanyReportRepository;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.EngineState;
import com.fbads.service.SettingsService;
import com.fbads.validation.Result;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Báo cáo lên hệ thống nội bộ của công ty theo các mốc 9h / 12h / 17h / 22h (bản Java của lib/companyReport.js).
 *  - Đến mốc: với mỗi Team đã cấu hình, cộng số Facebook của các chiến dịch thuộc Team thành một bản báo cáo rồi nhắn
 *    Telegram.
 *  - Chế độ "Chỉ xem": chỉ tạo bản báo cáo + nhắn Telegram, không bao giờ gửi lên công ty.
 *  - Chế độ "Duyệt trước": chỉ gửi khi người dùng bấm Gửi ở trang Báo cáo công ty.
 *  - Chế độ "Tự động gửi": đến mốc gửi luôn; số bất thường thì dừng cho người dùng xem; lỗi mạng / hệ thống công ty
 *    lỗi thì thử lại tối đa 3 lần, cách nhau 10 phút; sai mật khẩu / bị từ chối thì báo ngay.
 *  - Trước khi gửi luôn hỏi hệ thống công ty mốc đó đã có báo cáo chưa; có rồi thì không gửi đè (12h, 17h, 22h).
 *  - Riêng mốc 9h (chốt cả ngày hôm qua, chạy sáng hôm sau): bản ghi "9h ngày hôm qua" trên công ty đã có thì cập
 *    nhật vào đó (kèm lần sửa + lý do "Chốt số liệu cả ngày dd/mm"), chưa có thì tạo mới, đã khoá thì không đụng tới.
 *  - Báo cáo đã có trên công ty ở các mốc khác: người dùng bấm "Cập nhật lên công ty" (kèm lý do) mới sửa; đã khoá
 *    thì không sửa.
 *  - Cài đặt "làm sớm n phút": tạo / gửi báo cáo của mốc lúc (giờ mốc − n phút), báo cáo vẫn ghi đúng mốc.
 * Bản Java không có Telegram hai chiều: tin Telegram không có nút, người dùng xem / sửa / gửi trên trang Báo cáo công ty.
 * <p>
 * Class này là cửa vào (controller, engine gọi) và lo phần chạy theo mốc, sửa số, đồng bộ. Tạo bản nháp nằm ở
 * CompanyDrafts, gửi / cập nhật / tự gửi nằm ở CompanySender, tin Telegram + Nhật ký ở CompanyMessages.
 */
@Service
public class CompanyReportService {
    static final int GRACE_MIN = 10; // máy bật trễ tối đa 10 phút vẫn tạo báo cáo của mốc
    static final int SYNC_DAYS = 3;
    public static final String SOURCE = "Báo cáo công ty";
    public static final String AUTO_SOURCE = "Báo cáo công ty (tự động)";

    private final CompanyConfigRepository configs;
    private final CompanyReportRepository reports;
    private final CompanyApi api;
    private final CompanyDrafts drafts;
    private final CompanySender sender;
    private final EngineClock clock;
    private final EngineState state;
    private final SettingsService settings;

    public CompanyReportService(CompanyConfigRepository configs, CompanyReportRepository reports, CompanyApi api,
            CompanyDrafts drafts, CompanySender sender, EngineClock clock, EngineState state, SettingsService settings) {
        this.configs = configs;
        this.reports = reports;
        this.api = api;
        this.drafts = drafts;
        this.sender = sender;
        this.clock = clock;
        this.state = state;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ Cài đặt
    public CompanyConfig config() {
        long ws = WorkspaceContext.require();
        return configs.findById(ws).orElseGet(() -> configs.save(new CompanyConfig(ws)));
    }

    public List<CompanyReport> list() {
        return reports.findAllByOrderBySeqDesc();
    }

    public CompanyOverview overview() {
        return new CompanyOverview(config().publicView(), list());
    }

    public CompanyConfigSaved saveConfig(CompanyConfigPatch patch) {
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
        return new CompanyConfigSaved(saved.publicView());
    }

    /** Đăng nhập thử bằng tài khoản đã lưu → tên người dùng + danh sách Team để chọn */
    public CompanyLogin test() {
        CompanyConfig c = config();
        Map<String, String> user = api.test(c);
        return new CompanyLogin(user, api.listTeams(c));
    }

    public CompanyTeams teams() {
        return new CompanyTeams(api.listTeams(config()));
    }

    // ------------------------------------------------------------------ Chạy theo mốc
    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    /**
     * Tạo (hoặc làm mới số Facebook của) bản báo cáo cho mọi Team ở một mốc. Bản đã gửi thì giữ nguyên.
     * Số đã sửa tay và ghi chú được giữ lại khi làm mới. → danh sách bản báo cáo của mốc
     */
    public List<CompanyReport> createDrafts(int slot, String today, boolean silent) {
        return drafts.createDrafts(config(), slot, today, silent);
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
            List<CompanyReport> list = drafts.createDrafts(c, slot, now.date(), auto);
            if (auto) sender.autoSend(c, list);
        }
        if (auto) sender.retryDue(c);
    }

    /** Tạo bản báo cáo của một mốc ngay (không chờ đến giờ) */
    public CompanyBuilt buildNow(Double slotIn, boolean notify) {
        int slot = slotIn == null || slotIn != Math.rint(slotIn) ? -1 : (int) (double) slotIn;
        if (!CompanyRules.SLOTS.contains(slot)) throw new ApiException(400, "Mốc báo cáo không hợp lệ");
        if (config().getTeams().isEmpty()) {
            throw new ApiException(400, "Chưa có Team nào. Thêm Team ở Cài đặt → Báo cáo công ty.");
        }
        List<CompanyReport> list = createDrafts(slot, clock.now().date(), !notify);
        return new CompanyBuilt(list(), list.stream().map(CompanyReport::getId).toList());
    }

    // ------------------------------------------------------------------ Sửa, gửi, cập nhật
    private CompanyReport find(String id) {
        return reports.findById(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy bản báo cáo này"));
    }

    /**
     * Sửa số / ghi chú. Bản đã khoá trên công ty thì không sửa được. Số đã sửa tay không bị số Facebook ghi đè khi
     * làm mới.
     */
    public CompanyReport update(String id, CompanyReportPatch patch) {
        CompanyReport r = find(id);
        if (r.isOnRemote() && r.getRemote() != null && r.getRemote().locked()) {
            throw new ApiException(400, CompanySender.LOCKED_MSG);
        }
        Result<CompanyRules.PatchValue> v = CompanyRules.validateReportPatch(patch == null ? CompanyReportPatch.EMPTY : patch);
        if (!v.ok()) throw new ValidationException(v);
        if (!v.value().metrics().isEmpty()) {
            Set<String> edited = new LinkedHashSet<>(r.getEdited());
            Map<String, Long> metrics = new LinkedHashMap<>(r.getMetrics());
            v.value().metrics().forEach((k, val) -> {
                if (!Objects.equals(metrics.get(k), val) || !metrics.containsKey(k)) {
                    metrics.put(k, val);
                    edited.add(k);
                }
            });
            r.setMetrics(metrics);
            r.setEdited(new ArrayList<>(edited));
        }
        v.value().texts().forEach((k, s) -> {
            switch (k) {
                case "notes" -> r.setNotes(s);
                case "issue" -> r.setIssue(s);
                default -> r.setResolution(s);
            }
        });
        r.setUpdatedAt(now());
        return reports.save(r);
    }

    /** Bấm Gửi trên trang Báo cáo công ty. source: nguồn ghi Nhật ký. */
    public CompanyReport send(String id, String source) {
        CompanyReport r = find(id);
        return sender.send(config(), r, source);
    }

    /** Bấm "Cập nhật lên công ty" (kèm lý do) cho báo cáo đã có trên công ty */
    public CompanyReport updateRemote(String id, String reason, String source) {
        CompanyConfig c = config();
        return sender.updateRemote(c, find(id), reason, source);
    }

    public void remove(String id) {
        reports.delete(find(id));
    }

    // ------------------------------------------------------------------ Đồng bộ
    /**
     * Lấy trạng thái từ công ty (đã nộp / nộp muộn, lần sửa, đã khoá, số đã nộp) cho các bản báo cáo gần đây.
     * Bản chưa gửi mà công ty đã có (nộp tay trên web) → "Công ty đã có" kèm số trên công ty để so. → số bản đã cập nhật
     */
    public int sync() {
        CompanyConfig c = config();
        if (!c.isEnabled() || c.getEmail().isEmpty() || settings.get().isMock()) return 0;
        String since = now().minusSeconds(SYNC_DAYS * 86_400L).atOffset(ZoneOffset.UTC).toLocalDate().toString();
        Map<String, List<CompanyReport>> byTeam = new LinkedHashMap<>();
        for (CompanyReport r : reports.findByDateGreaterThanEqual(since)) {
            byTeam.computeIfAbsent(r.getTeamId(), k -> new ArrayList<>()).add(r);
        }
        int n = 0;
        for (Map.Entry<String, List<CompanyReport>> e : byTeam.entrySet()) {
            List<String> dates = e.getValue().stream().map(CompanyReport::getDate).sorted().toList();
            List<RemoteReport> remote = api.listReports(c, e.getKey(), dates.getFirst(), dates.getLast());
            for (CompanyReport r : e.getValue()) {
                RemoteReport x = remote.stream().filter(y -> y.isFor(r.getDate(), r.getSlot())).findFirst().orElse(null);
                if (x == null) continue;
                r.setRemote(sender.remoteOf(x, null));
                r.setRemoteId(r.getRemote().id());
                r.setRemoteStatus(r.getRemote().status());
                // mốc 9h: bản ghi đã có trên công ty chính là chỗ tool sẽ cập nhật vào, nên vẫn để chờ gửi
                if (!List.of("sent", "exists", "retry").contains(r.getStatus()) && !sender.isSending(r.getId())
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
}
