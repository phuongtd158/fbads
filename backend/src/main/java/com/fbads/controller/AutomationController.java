package com.fbads.controller;

import com.fbads.dto.RuleRequest;
import com.fbads.dto.Saved;
import com.fbads.dto.ScheduleRequest;
import com.fbads.service.ReportService;
import com.fbads.service.RuleService;
import com.fbads.service.ScheduleService;
import com.fbads.service.TelegramService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Lịch tự động, rule và báo cáo Telegram. Chỉ nhận/trả HTTP, việc thật nằm ở ScheduleService, RuleService, ReportService. */
@RestController
@RequestMapping("/api")
public class AutomationController {
    private static final Map<String, Object> OK = Map.of("ok", true);

    private final ScheduleService schedules;
    private final RuleService rules;
    private final ReportService report;
    private final JsonMapper mapper;

    public AutomationController(ScheduleService schedules, RuleService rules, ReportService report, JsonMapper mapper) {
        this.schedules = schedules;
        this.rules = rules;
        this.report = report;
        this.mapper = mapper;
    }

    /** Trả mục đã lưu kèm cảnh báo: { ...mục, warnings } như bản Node */
    private ObjectNode withWarnings(Saved<?> saved) {
        ObjectNode out = mapper.valueToTree(saved.item());
        out.set("warnings", mapper.valueToTree(saved.warnings()));
        return out;
    }

    // ----- Lịch -----
    @PostMapping("/schedules")
    ObjectNode saveSchedule(@RequestBody(required = false) ScheduleRequest b) { return withWarnings(schedules.save(b)); }

    @DeleteMapping("/schedules/{id}")
    Map<String, Object> deleteSchedule(@PathVariable String id) {
        schedules.delete(id);
        return OK;
    }

    @PostMapping("/schedules/{id}/run")
    Map<String, Object> runSchedule(@PathVariable String id) {
        schedules.runNow(id);
        return OK;
    }

    // ----- Rule -----
    @GetMapping("/rules/activity")
    Map<String, Object> activity() { return rules.activity(); }

    @PostMapping("/rules/run")
    Map<String, Object> runRules() {
        rules.runNow();
        return OK;
    }

    @PostMapping("/rules/preview")
    Map<String, Object> preview(@RequestBody(required = false) RuleRequest b) { return rules.preview(b); }

    @PostMapping("/rules")
    ObjectNode saveRule(@RequestBody(required = false) RuleRequest b) { return withWarnings(rules.save(b)); }

    @DeleteMapping("/rules/{id}")
    Map<String, Object> deleteRule(@PathVariable String id) {
        rules.delete(id);
        return OK;
    }

    // ----- Báo cáo -----
    @PostMapping("/report")
    ResponseEntity<?> sendReport() {
        TelegramService.Reply out = TelegramService.reply(report.send());
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
