package com.fbads.controller;

import com.fbads.dto.Responses.Ok;
import com.fbads.dto.Responses.RuleActivity;
import com.fbads.dto.Responses.RulePreview;
import com.fbads.dto.RuleRequest;
import com.fbads.dto.Saved;
import com.fbads.dto.ScheduleRequest;
import com.fbads.entity.Rule;
import com.fbads.entity.Schedule;
import com.fbads.notify.SendResult;
import com.fbads.service.ReportService;
import com.fbads.service.RuleService;
import com.fbads.service.ScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lịch tự động, rule và báo cáo Telegram. Chỉ nhận/trả HTTP, việc thật nằm ở ScheduleService, RuleService, ReportService. */
@RestController
@RequestMapping("/api")
public class AutomationController {
    private final ScheduleService schedules;
    private final RuleService rules;
    private final ReportService report;

    public AutomationController(ScheduleService schedules, RuleService rules, ReportService report) {
        this.schedules = schedules;
        this.rules = rules;
        this.report = report;
    }

    // ----- Lịch -----
    @PostMapping("/schedules")
    Saved<Schedule> saveSchedule(@RequestBody(required = false) ScheduleRequest b) { return schedules.save(b); }

    @DeleteMapping("/schedules/{id}")
    Ok deleteSchedule(@PathVariable String id) {
        schedules.delete(id);
        return Ok.OK;
    }

    @PostMapping("/schedules/{id}/run")
    Ok runSchedule(@PathVariable String id) {
        schedules.runNow(id);
        return Ok.OK;
    }

    // ----- Rule -----
    @GetMapping("/rules/activity")
    Map<String, RuleActivity> activity() { return rules.activity(); }

    @PostMapping("/rules/run")
    Ok runRules() {
        rules.runNow();
        return Ok.OK;
    }

    @PostMapping("/rules/preview")
    RulePreview preview(@RequestBody(required = false) RuleRequest b) { return rules.preview(b); }

    @PostMapping("/rules")
    Saved<Rule> saveRule(@RequestBody(required = false) RuleRequest b) { return rules.save(b); }

    @DeleteMapping("/rules/{id}")
    Ok deleteRule(@PathVariable String id) {
        rules.delete(id);
        return Ok.OK;
    }

    // ----- Báo cáo -----
    private static final String NO_CHANNEL = "Chưa có kênh thông báo nào nhận Báo cáo. Thêm ở Cài đặt → Thông báo.";

    @PostMapping("/report")
    ResponseEntity<?> sendReport() {
        SendResult.Reply out = report.send().reply(NO_CHANNEL);
        return ResponseEntity.status(out.status()).body(out.body());
    }

    @PostMapping("/report/weekly")
    ResponseEntity<?> sendWeeklyReport() {
        SendResult.Reply out = report.sendWeekly().reply(NO_CHANNEL);
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
