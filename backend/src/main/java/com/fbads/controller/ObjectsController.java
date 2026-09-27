package com.fbads.controller;

import com.fbads.dto.Requests;
import com.fbads.entity.LogEntry;
import com.fbads.service.LogService;
import com.fbads.service.ObjectService;
import com.fbads.service.UndoService;
import jakarta.validation.Valid;
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

/** Camp / nhóm QC, nhật ký và hoàn tác. Chỉ nhận/trả HTTP, việc thật nằm ở ObjectService, LogService, UndoService. */
@RestController
@RequestMapping("/api")
public class ObjectsController {
    private static final Map<String, Object> OK = Map.of("ok", true);

    private final ObjectService objects;
    private final LogService logs;
    private final UndoService undo;

    public ObjectsController(ObjectService objects, LogService logs, UndoService undo) {
        this.objects = objects;
        this.logs = logs;
        this.undo = undo;
    }

    @GetMapping("/objects")
    Map<String, Object> list(@RequestParam(required = false) String refresh) { return objects.list("1".equals(refresh)); }

    @GetMapping("/insights")
    Map<String, Object> insights(@RequestParam Map<String, String> q) { return objects.insights(q); }

    @PostMapping("/objects/{id}/status")
    Map<String, Object> status(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        objects.setStatus(id, b != null && b.path("on").asBoolean(false), b == null ? null : b.path("name").asString(null));
        return OK;
    }

    /** Số tiền kiểm tra bằng Bean Validation (@Valid + chú thích trong Requests.Budget) */
    @PostMapping("/objects/{id}/budget")
    Map<String, Object> budget(@PathVariable String id, @Valid @RequestBody Requests.Budget b) {
        objects.setBudget(id, b.amount(), b.name());
        return OK;
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
