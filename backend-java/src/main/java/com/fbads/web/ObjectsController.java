package com.fbads.web;

import com.fbads.common.Fmt;
import com.fbads.engine.UndoService;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.DateRanges;
import com.fbads.facebook.FacebookService;
import com.fbads.facebook.FbException;
import com.fbads.logs.LogEntry;
import com.fbads.logs.LogService;
import com.fbads.settings.SettingsService;
import com.fbads.validation.Requests;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Camp / nhóm QC: danh sách, số liệu theo khoảng ngày, bật/tắt, ngân sách, nhật ký và hoàn tác. */
@RestController
@RequestMapping("/api")
public class ObjectsController {
    private static final Map<String, Object> OK = Map.of("ok", true);

    private final FacebookService fb;
    private final SettingsService settings;
    private final LogService logs;
    private final UndoService undo;

    public ObjectsController(FacebookService fb, SettingsService settings, LogService logs, UndoService undo) {
        this.fb = fb;
        this.settings = settings;
        this.logs = logs;
        this.undo = undo;
    }

    @GetMapping("/objects")
    Map<String, Object> objects(@RequestParam(required = false) String refresh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", fb.listObjects("1".equals(refresh)));
        m.putAll(fb.objectsMeta());
        return m;
    }

    /** Số liệu theo khoảng ngày cho Tổng quan: ?range=last_7d hoặc ?since=…&until=… (trống = hôm nay) */
    @GetMapping("/insights")
    ResponseEntity<?> insights(@RequestParam Map<String, String> q) {
        String today = DateRanges.todayIn(settings.get().getTimezone());
        DateRanges.Parsed parsed = DateRanges.parse(q, today);
        if (!parsed.ok()) return ApiExceptionHandler.error(400, parsed.error());
        DateRanges.Resolved range = DateRanges.resolve(parsed.spec(), today);
        FacebookService.RangeResult got = fb.rangeData(new FacebookService.RangeQuery(parsed.key(), DateRanges.fbParams(parsed.spec(), today), range.days()),
                "1".equals(q.get("refresh")));
        Map<String, Object> meta = fb.objectsMeta();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", parsed.spec().toJson());
        m.put("key", parsed.key());
        m.put("since", range.since());
        m.put("until", range.until());
        m.put("days", range.days());
        m.put("at", got.at() == null || got.at() == 0 ? null : got.at());
        m.put("stale", got.stale());
        m.put("blockedUntil", meta.get("blockedUntil"));
        m.put("usage", meta.get("usage"));
        m.put("accountErrors", meta.get("accountErrors"));
        m.put("metrics", got.data());
        return ResponseEntity.ok(m);
    }

    /** Thao tác tay: ghi nhật ký (kể cả khi lỗi) rồi ném lại lỗi cho giao diện */
    private void manual(String id, String name, Map<String, Object> action, String okDetail, Map<String, Object> after, Runnable fn) {
        AdObject cur = fb.findCached(id);
        String label = name == null || name.isEmpty() ? id : name;
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", id);
        target.put("name", label);
        if (cur != null) {
            target.put("level", cur.level);
            if (cur.accountId != null) { target.put("accountId", cur.accountId); target.put("accountName", cur.accountName); }
        }
        Map<String, Object> before = FacebookService.snapshot(cur);
        String mode = settings.get().mode();
        java.util.function.Consumer<LogEntry> base = e -> {
            e.setKind("manual"); e.setSource("Thủ công"); e.setName(label); e.setTarget(target); e.setAction(action); e.setBefore(before); e.setMode(mode);
        };
        try {
            fn.run();
            logs.log(e -> { base.accept(e); e.setDetail(okDetail); e.setOk(true); e.setAfter(after); });
        } catch (RuntimeException ex) {
            logs.log(e -> { base.accept(e); e.setDetail(ex.getMessage()); e.setOk(false); e.setError(FbException.describe(ex)); });
            throw ex;
        }
    }

    @PostMapping("/objects/{id}/status")
    Map<String, Object> status(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        boolean on = b != null && b.path("on").asBoolean(false);
        String name = b == null ? null : b.path("name").asString(null);
        manual(id, name, Map.of("type", on ? "on" : "off"), on ? "Bật camp" : "Tắt camp", Map.of("status", on ? "ACTIVE" : "PAUSED"), () -> fb.setStatus(id, on));
        return OK;
    }

    /** Đặt ngân sách: số tiền kiểm tra bằng Bean Validation (@Valid + chú thích trong Requests.Budget) */
    @PostMapping("/objects/{id}/budget")
    ResponseEntity<?> budget(@PathVariable String id, @Valid @RequestBody Requests.Budget b) {
        long value = Math.round(b.amount());
        AdObject cur = fb.findCached(id);
        if (cur != null && cur.dailyBudget == null)
            return ApiExceptionHandler.error(400, "Mục này không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO). Hãy chỉnh ở cấp có ngân sách.");
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "budget"); action.put("mode", "set"); action.put("value", value);
        manual(id, b.name(), action, "Đặt ngân sách " + Fmt.money(value), Map.of("dailyBudget", value), () -> fb.setBudget(id, value));
        return ResponseEntity.ok(OK);
    }

    @GetMapping("/logs")
    List<LogEntry> logs() { return logs.recent(300); }

    @PostMapping("/logs/{id}/undo")
    Map<String, Object> undo(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        LogEntry entry = undo.undo(id, b != null && b.path("force").asBoolean(false));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("entry", entry);
        return m;
    }
}
