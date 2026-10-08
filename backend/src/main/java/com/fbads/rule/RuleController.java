package com.fbads.rule;

import com.fbads.web.Ok;
import com.fbads.web.Saved;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API rule: lưu, xoá, xem trước, chạy ngay, hoạt động 7 ngày. Việc thật nằm ở RuleService. */
@RestController
@RequestMapping("/api/rules")
public class RuleController {
    private final RuleService rules;

    public RuleController(RuleService rules) { this.rules = rules; }

    @GetMapping("/activity")
    Map<String, RuleActivity> activity() { return rules.activity(); }

    @PostMapping("/run")
    Ok run() {
        rules.runNow();
        return Ok.OK;
    }

    @PostMapping("/preview")
    RulePreview preview(@RequestBody(required = false) RuleRequest b) { return rules.preview(b); }

    @PostMapping
    Saved<Rule> save(@RequestBody(required = false) RuleRequest b) { return rules.save(b); }

    @DeleteMapping("/{id}")
    Ok delete(@PathVariable String id) {
        rules.delete(id);
        return Ok.OK;
    }
}
