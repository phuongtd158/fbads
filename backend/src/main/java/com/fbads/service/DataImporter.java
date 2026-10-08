package com.fbads.service;

import com.fbads.common.UpstashCodec;
import com.fbads.config.AppProperties;
import com.fbads.log.LogEntry;
import com.fbads.log.LogRepository;
import com.fbads.rule.Rule;
import com.fbads.rule.RuleRepository;
import com.fbads.schedule.Schedule;
import com.fbads.schedule.ScheduleRepository;
import com.fbads.security.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Nhập dữ liệu của bản Node (data.json, hoặc bản mã hoá trên Upstash) vào MySQL — chỉ chạy khi DB còn trống,
 * nên đặt IMPORT_FILE rồi khởi động lại nhiều lần cũng không nhập trùng.
 * Nhập vào workspace 1: cài đặt, mật khẩu đã băm (thành tài khoản "admin"), lịch, rule, nhật ký, các camp đang chờ bật lại hôm sau.
 * Không nhập các dấu "đã chạy hôm nay" (fired, lastRule…): engine tự làm lại từ đầu, lịch đã qua giờ quá 10 phút không chạy lại.
 */
@Service
@Order(50)
public class DataImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataImporter.class);

    /**
     * File dữ liệu của bản Node. settings giữ dạng Map vì chỉ những khoá có trong file mới được ghi đè lên cài đặt
     * hiện tại; các phần còn lại Jackson đọc thẳng vào entity.
     */
    public record LegacyData(Map<String, Object> settings, List<Schedule> schedules, List<Rule> rules, List<LogEntry> logs,
            LegacyState state) {
        public LegacyData {
            schedules = schedules == null ? List.of() : schedules;
            rules = rules == null ? List.of() : rules;
            logs = logs == null ? List.of() : logs;
        }

        /** Mật khẩu đã băm của bản Node ("" = chưa đặt) */
        String passwordHash() {
            return settings != null && settings.get("passwordHash") instanceof String h ? h : "";
        }

        /** Các camp đang chờ bật lại hôm sau */
        Collection<Resume> resumes() {
            return state == null || state.resume() == null ? List.of() : state.resume().values();
        }
    }

    public record LegacyState(Map<String, Resume> resume) {}

    /** Một camp rule đã tắt và hẹn bật lại vào ngày sau ngày date */
    public record Resume(String ruleId, String objId, String date) {
        public Resume {
            ruleId = ruleId == null ? "" : ruleId;
            objId = objId == null ? "" : objId;
            date = date == null ? "" : date;
        }
    }

    /** Trả lời của Upstash cho lệnh GET: { result: chuỗi đã mã hoá | null } */
    private record UpstashResult(String result) {}

    private final AppProperties props;
    private final SettingsService settings;
    private final ScheduleRepository schedules;
    private final RuleRepository rules;
    private final LogRepository logs;
    private final EngineState state;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;
    private final RestClient.Builder http;
    private final AuthService auth;

    public DataImporter(AppProperties props, AuthService auth, SettingsService settings,
            ScheduleRepository schedules, RuleRepository rules, LogRepository logs, EngineState state,
            TransactionTemplate tx, JsonMapper mapper, RestClient.Builder http) {
        this.props = props;
        this.auth = auth;
        this.settings = settings;
        this.schedules = schedules;
        this.rules = rules;
        this.logs = logs;
        this.state = state;
        this.tx = tx;
        this.mapper = mapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.http = http;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        AppProperties.Import cfg = props.importer();
        boolean fromFile = cfg.file() != null && !cfg.file().isBlank();
        if (!fromFile && !cfg.upstash()) return;
        if (WorkspaceContext.call(WorkspaceContext.DEFAULT, () -> schedules.count() > 0 || rules.count() > 0 || logs.count() > 0)) {
            log.info("DB đã có dữ liệu, bỏ qua bước nhập dữ liệu cũ.");
            return;
        }
        String json = fromFile ? Files.readString(Path.of(cfg.file())) : fetchUpstash(cfg);
        importData(mapper.readValue(json, LegacyData.class));
    }

    private String fetchUpstash(AppProperties.Import cfg) {
        if (cfg.dataKey() == null || cfg.dataKey().length() < 16)
            throw new IllegalStateException("Thiếu DATA_KEY (ít nhất 16 ký tự) để giải mã dữ liệu trên Upstash.");
        String body = http.build().post().uri(cfg.upstashUrl().replaceAll("/+$", ""))
                .header("Authorization", "Bearer " + cfg.upstashToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of("GET", "fbads:data"))
                .retrieve().body(String.class);
        UpstashResult res = body == null ? null : mapper.readValue(body, UpstashResult.class);
        if (res == null || res.result() == null) throw new IllegalStateException("Upstash chưa có dữ liệu (khoá fbads:data trống).");
        return UpstashCodec.decode(res.result(), cfg.dataKey());
    }

    /** Nhập toàn bộ trong 1 giao dịch: lỗi giữa chừng thì DB vẫn trống như trước */
    public void importData(LegacyData d) {
        WorkspaceContext.run(WorkspaceContext.DEFAULT, () -> importInto(d)); // dữ liệu của bản 1 người dùng → workspace 1
    }

    private void importInto(LegacyData d) {
        tx.executeWithoutResult(t -> {
            auth.importLegacyPassword(d.passwordHash()); // mật khẩu cũ → tài khoản "admin"
            if (d.settings() != null) {
                settings.update(x -> {
                    mapper.updateValue(x, d.settings());
                    // dữ liệu cũ chỉ có 1 tài khoản quảng cáo → chuyển sang danh sách
                    if ((x.getAdAccountIds() == null || x.getAdAccountIds().isEmpty()) && x.getAdAccountId() != null
                            && !x.getAdAccountId().isEmpty())
                        x.setAdAccountIds(new ArrayList<>(List.of(x.getAdAccountId())));
                });
            }
            schedules.saveAll(d.schedules());
            rules.saveAll(d.rules());
            // bản Node lưu nhật ký mới nhất trước → nhập ngược lại để thứ tự (seq) tăng theo thời gian
            for (LogEntry e : d.logs().reversed()) logs.save(e);
            for (Resume p : d.resumes()) state.addResume(p.ruleId(), p.objId(), p.date());
        });
        settings.reload();
        log.info("Đã nhập dữ liệu cũ vào workspace 1: {} lịch, {} rule, {} dòng nhật ký.", schedules.count(), rules.count(), logs.count());
    }
}
