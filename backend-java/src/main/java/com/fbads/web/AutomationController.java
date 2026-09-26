package com.fbads.web;

import com.fbads.automation.Rule;
import com.fbads.automation.RuleRepository;
import com.fbads.automation.Schedule;
import com.fbads.automation.ScheduleRepository;
import com.fbads.common.Ids;
import com.fbads.engine.EngineLock;
import com.fbads.engine.ReportService;
import com.fbads.engine.RuleRunner;
import com.fbads.engine.ScheduleRunner;
import com.fbads.facebook.FacebookService;
import com.fbads.notify.TelegramService;
import com.fbads.settings.SettingsService;
import com.fbads.validation.Result;
import com.fbads.validation.RuleValidator;
import com.fbads.validation.ScheduleValidator;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Lịch tự động, rule và báo cáo Telegram. */
@RestController
@RequestMapping("/api")
public class AutomationController {
    static final int MAX_ITEMS = 200;

    private final ScheduleRepository schedules;
    private final RuleRepository rules;
    private final FacebookService fb;
    private final SettingsService settings;
    private final ScheduleRunner scheduleRunner;
    private final RuleRunner ruleRunner;
    private final ReportService report;
    private final EngineLock lock;
    private final JsonMapper mapper;

    public AutomationController(ScheduleRepository schedules, RuleRepository rules, FacebookService fb, SettingsService settings,
                                ScheduleRunner scheduleRunner, RuleRunner ruleRunner, ReportService report, EngineLock lock, JsonMapper mapper) {
        this.schedules = schedules;
        this.rules = rules;
        this.fb = fb;
        this.settings = settings;
        this.scheduleRunner = scheduleRunner;
        this.ruleRunner = ruleRunner;
        this.report = report;
        this.lock = lock;
        this.mapper = mapper;
    }

    private static JsonNode body(JsonNode b) { return b == null || !b.isObject() ? JsonNodeFactory.instance.objectNode() : b; }

    /** Lưu (thêm/sửa) một lịch hoặc rule đã qua kiểm tra; trả mục đã lưu kèm cảnh báo */
    private <T> ResponseEntity<?> saveItem(Result<T> r, long count, Function<T, String> getId, Consumer<T> assignId, CrudRepository<T, String> repo) {
        if (!r.ok()) return ApiExceptionHandler.bad(r);
        T item = r.value();
        String id = getId.apply(item);
        boolean isNew = id == null || id.isEmpty() || !repo.existsById(id);
        if (isNew && count >= MAX_ITEMS)
            return ApiExceptionHandler.error(400, "Đã đạt giới hạn " + MAX_ITEMS + " mục. Hãy xoá bớt trước khi thêm mới.");
        if (id == null || id.isEmpty()) assignId.accept(item);
        T saved = repo.save(item);
        ObjectNode out = mapper.valueToTree(saved);
        out.set("warnings", mapper.valueToTree(r.warnings()));
        return ResponseEntity.ok(out);
    }

    private List<RuleValidator.Account> accounts() {
        return fb.accounts().stream().map(a -> new RuleValidator.Account((String) a.get("id"), (String) a.get("name"))).toList();
    }

    private Result<Rule> validateRule(JsonNode b, List<Rule> existing) {
        return RuleValidator.validate(b, fb.objectsForValidation(), existing, settings.get().getAccountTargets(), accounts());
    }

    // ----- Lịch -----
    @PostMapping("/schedules")
    ResponseEntity<?> saveSchedule(@RequestBody(required = false) JsonNode b) {
        List<Schedule> list = schedules.findAllByOrderBySeqAsc();
        return saveItem(ScheduleValidator.validate(body(b), fb.objectsForValidation(), list), list.size(), Schedule::getId, s -> s.setId(Ids.uid()), schedules);
    }

    @DeleteMapping("/schedules/{id}")
    Map<String, Object> deleteSchedule(@PathVariable String id) {
        schedules.deleteById(id);
        return Map.of("ok", true);
    }

    @PostMapping("/schedules/{id}/run")
    ResponseEntity<?> runSchedule(@PathVariable String id) {
        Schedule s = schedules.findById(id).orElse(null);
        if (s == null) return ApiExceptionHandler.error(404, "Không tìm thấy lịch");
        if (ScheduleRunner.BLOCKED.equals(lock.run(() -> scheduleRunner.run(s, null)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "Facebook đang giới hạn số lần gọi nên lịch chưa chạy. Thử lại sau vài phút.");
            m.put("rateLimited", true);
            return ResponseEntity.status(429).body(m);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ----- Rule -----
    @GetMapping("/rules/activity")
    Map<String, Object> activity() { return ruleRunner.activity(); }

    @PostMapping("/rules/run")
    Map<String, Object> runRules() {
        lock.run(() -> { ruleRunner.runRules(); return null; });
        return Map.of("ok", true);
    }

    /** Xem trước: rule (chưa lưu) đang khớp camp nào ngay bây giờ — không thay đổi gì */
    @PostMapping("/rules/preview")
    ResponseEntity<?> preview(@RequestBody(required = false) JsonNode b) {
        ObjectNode input = ((ObjectNode) body(b)).deepCopy();
        input.put("enabled", true);
        Result<Rule> v = validateRule(input, List.of());
        if (!v.ok()) return ApiExceptionHandler.bad(v);
        Map<String, Object> out = new LinkedHashMap<>(ruleRunner.preview(v.value()));
        out.put("warnings", v.warnings());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/rules")
    ResponseEntity<?> saveRule(@RequestBody(required = false) JsonNode b) {
        List<Rule> list = rules.findAllByOrderBySeqAsc();
        return saveItem(validateRule(body(b), list), list.size(), Rule::getId, r -> r.setId(Ids.uid()), rules);
    }

    @DeleteMapping("/rules/{id}")
    Map<String, Object> deleteRule(@PathVariable String id) {
        rules.deleteById(id);
        return Map.of("ok", true);
    }

    // ----- Báo cáo -----
    @PostMapping("/report")
    ResponseEntity<?> sendReport() {
        TelegramService.Reply out = TelegramService.reply(report.send());
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
