package com.fbads.importer;

import com.fbads.automation.Rule;
import com.fbads.automation.RuleRepository;
import com.fbads.automation.Schedule;
import com.fbads.automation.ScheduleRepository;
import com.fbads.config.AppProperties;
import com.fbads.engine.state.EngineState;
import com.fbads.logs.LogEntry;
import com.fbads.logs.LogRepository;
import com.fbads.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nhập dữ liệu của bản Node (data.json, hoặc bản mã hoá trên Upstash) vào MariaDB — chỉ chạy khi DB còn trống,
 * nên đặt IMPORT_FILE rồi khởi động lại nhiều lần cũng không nhập trùng.
 * Nhập: cài đặt (kể cả mật khẩu đã băm), lịch, rule, nhật ký, các camp đang chờ bật lại hôm sau.
 * Không nhập các dấu "đã chạy hôm nay" (fired, lastRule…): engine tự làm lại từ đầu, lịch đã qua giờ quá 10 phút không chạy lại.
 */
@Component
@Order(50)
public class DataImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataImporter.class);

    private final AppProperties props;
    private final SettingsService settings;
    private final ScheduleRepository schedules;
    private final RuleRepository rules;
    private final LogRepository logs;
    private final EngineState state;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;
    private final RestClient.Builder http;

    public DataImporter(AppProperties props, SettingsService settings, ScheduleRepository schedules, RuleRepository rules, LogRepository logs,
                        EngineState state, TransactionTemplate tx, JsonMapper mapper, RestClient.Builder http) {
        this.props = props;
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
        if (schedules.count() > 0 || rules.count() > 0 || logs.count() > 0) {
            log.info("DB đã có dữ liệu, bỏ qua bước nhập dữ liệu cũ.");
            return;
        }
        String json = fromFile ? Files.readString(Path.of(cfg.file())) : fetchUpstash(cfg);
        importJson(mapper.readTree(json));
    }

    private String fetchUpstash(AppProperties.Import cfg) {
        if (cfg.dataKey() == null || cfg.dataKey().length() < 16) throw new IllegalStateException("Thiếu DATA_KEY (ít nhất 16 ký tự) để giải mã dữ liệu trên Upstash.");
        JsonNode res = http.build().post().uri(cfg.upstashUrl().replaceAll("/+$", ""))
                .header("Authorization", "Bearer " + cfg.upstashToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of("GET", "fbads:data"))
                .retrieve().body(JsonNode.class);
        JsonNode result = res == null ? null : res.get("result");
        if (result == null || result.isNull()) throw new IllegalStateException("Upstash chưa có dữ liệu (khoá fbads:data trống).");
        return UpstashCodec.decode(result.asString(), cfg.dataKey());
    }

    /** Nhập toàn bộ trong 1 giao dịch: lỗi giữa chừng thì DB vẫn trống như trước */
    public void importJson(JsonNode d) {
        tx.executeWithoutResult(t -> {
            JsonNode s = d.path("settings");
            if (s.isObject()) {
                settings.update(x -> {
                    mapper.updateValue(x, s);
                    // dữ liệu cũ chỉ có 1 tài khoản quảng cáo → chuyển sang danh sách
                    if ((x.getAdAccountIds() == null || x.getAdAccountIds().isEmpty()) && x.getAdAccountId() != null && !x.getAdAccountId().isEmpty())
                        x.setAdAccountIds(new ArrayList<>(List.of(x.getAdAccountId())));
                });
            }
            for (JsonNode n : d.path("schedules").values()) schedules.save(mapper.treeToValue(n, Schedule.class));
            for (JsonNode n : d.path("rules").values()) rules.save(mapper.treeToValue(n, Rule.class));
            // bản Node lưu nhật ký mới nhất trước → nhập ngược lại để thứ tự (seq) tăng theo thời gian
            List<JsonNode> entries = new ArrayList<>();
            for (JsonNode n : d.path("logs").values()) entries.add(n);
            for (int i = entries.size() - 1; i >= 0; i--) logs.save(mapper.treeToValue(entries.get(i), LogEntry.class));
            for (Map.Entry<String, JsonNode> e : d.path("state").path("resume").properties()) {
                JsonNode p = e.getValue();
                state.addResume(p.path("ruleId").asString(""), p.path("objId").asString(""), p.path("date").asString(""));
            }
        });
        settings.reload();
        log.info("Đã nhập dữ liệu cũ: {} lịch, {} rule, {} dòng nhật ký.", schedules.count(), rules.count(), logs.count());
    }
}
