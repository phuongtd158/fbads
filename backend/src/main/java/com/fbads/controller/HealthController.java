package com.fbads.controller;

import com.fbads.dto.Responses.Health;
import com.fbads.engine.EngineWatch;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * /api/health cho UptimeRobot / Render: 503 khi vòng lịch/rule không chạy xong lượt nào trong 5 phút
 * (web còn sống chưa chắc lịch còn chạy). Không cần đăng nhập, nhận cả HEAD. /actuator/health chỉ cho biết ứng dụng còn sống.
 */
@RestController
@RequestMapping("/api")
public class HealthController {
    private final EngineWatch watch;

    public HealthController(EngineWatch watch) { this.watch = watch; }

    @GetMapping("/health")
    ResponseEntity<Health> health() {
        Health h = watch.health();
        return ResponseEntity.status(h.ok() ? 200 : 503).body(h);
    }
}
