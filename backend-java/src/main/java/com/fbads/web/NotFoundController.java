package com.fbads.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API không tồn tại → 404 JSON (không rơi xuống trang index.html của giao diện) */
@RestController
public class NotFoundController {
    @RequestMapping("/api/**")
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Not found"));
    }
}
