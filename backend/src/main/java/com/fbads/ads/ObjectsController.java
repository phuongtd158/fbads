package com.fbads.ads;

import com.fbads.dto.Responses.Ok;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API camp / nhóm QC: danh sách, số liệu, xu hướng, bật/tắt, đổi ngân sách. Việc thật nằm ở ObjectService. */
@RestController
@RequestMapping("/api")
public class ObjectsController {
    private final ObjectService objects;

    public ObjectsController(ObjectService objects) { this.objects = objects; }

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
    Ok status(@PathVariable String id, @RequestBody(required = false) StatusChange body) {
        StatusChange b = body == null ? StatusChange.EMPTY : body;
        objects.setStatus(id, b.on(), b.name());
        return Ok.OK;
    }

    /** Số tiền kiểm tra bằng Bean Validation (@Valid + chú thích trong Budget) */
    @PostMapping("/objects/{id}/budget")
    Ok budget(@PathVariable String id, @Valid @RequestBody Budget b) {
        objects.setBudget(id, b.amount(), b.name());
        return Ok.OK;
    }
}
