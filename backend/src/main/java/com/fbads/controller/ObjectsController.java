package com.fbads.controller;

import com.fbads.dto.Requests;
import com.fbads.dto.Responses.Insights;
import com.fbads.dto.Responses.ObjectsList;
import com.fbads.dto.Responses.Ok;
import com.fbads.dto.Responses.Trend;
import com.fbads.dto.Responses.Undone;
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

import java.util.List;
import java.util.Map;

/** Camp / nhóm QC, nhật ký và hoàn tác. Chỉ nhận/trả HTTP, việc thật nằm ở ObjectService, LogService, UndoService. */
@RestController
@RequestMapping("/api")
public class ObjectsController {
    private final ObjectService objects;
    private final LogService logs;
    private final UndoService undo;

    public ObjectsController(ObjectService objects, LogService logs, UndoService undo) {
        this.objects = objects;
        this.logs = logs;
        this.undo = undo;
    }

    @GetMapping("/objects")
    ObjectsList list(@RequestParam(required = false) String refresh) {
        return objects.list("1".equals(refresh));
    }

    @GetMapping("/insights")
    Insights insights(@RequestParam Map<String, String> q) {
        return objects.insights(q);
    }

    @GetMapping("/objects/{id}/trend")
    Trend trend(@PathVariable String id, @RequestParam(required = false) String days,
            @RequestParam(required = false) String refresh) {
        return objects.trend(id, days, "1".equals(refresh));
    }

    @PostMapping("/objects/{id}/status")
    Ok status(@PathVariable String id, @RequestBody(required = false) Requests.StatusChange body) {
        Requests.StatusChange b = body == null ? Requests.StatusChange.EMPTY : body;
        objects.setStatus(id, b.on(), b.name());
        return Ok.OK;
    }

    /** Số tiền kiểm tra bằng Bean Validation (@Valid + chú thích trong Requests.Budget) */
    @PostMapping("/objects/{id}/budget")
    Ok budget(@PathVariable String id, @Valid @RequestBody Requests.Budget b) {
        objects.setBudget(id, b.amount(), b.name());
        return Ok.OK;
    }

    @GetMapping("/logs")
    List<LogEntry> logs() {
        return logs.recent(300);
    }

    @PostMapping("/logs/{id}/undo")
    Undone undo(@PathVariable String id, @RequestBody(required = false) Requests.Undo b) {
        return new Undone(true, undo.undo(id, (b == null ? Requests.Undo.EMPTY : b).force()));
    }
}
