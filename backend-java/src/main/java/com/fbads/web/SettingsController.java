package com.fbads.web;

import com.fbads.facebook.FacebookService;
import com.fbads.automation.RuleRepository;
import com.fbads.automation.ScheduleRepository;
import com.fbads.notify.TelegramService;
import com.fbads.settings.SettingsService;
import com.fbads.validation.Result;
import com.fbads.validation.SettingsValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trạng thái chung, cài đặt và thử kết nối Telegram. */
@RestController
@RequestMapping("/api")
public class SettingsController {
    /** Đổi nguồn dữ liệu → bỏ cache cũ */
    private static final List<String> SOURCE_KEYS = List.of("mock", "adAccountId", "adAccountIds", "accessToken");

    private final SettingsService settings;
    private final ScheduleRepository schedules;
    private final RuleRepository rules;
    private final FacebookService fb;
    private final TelegramService telegram;

    public SettingsController(SettingsService settings, ScheduleRepository schedules, RuleRepository rules, FacebookService fb, TelegramService telegram) {
        this.settings = settings;
        this.schedules = schedules;
        this.rules = rules;
        this.fb = fb;
        this.telegram = telegram;
    }

    /** Nơi lưu dữ liệu (hiện ở Cài đặt → Chung) */
    static Map<String, Object> storage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", "db");
        m.put("provider", "MariaDB");
        m.put("lastSavedAt", null);
        m.put("lastError", "");
        m.put("pending", false);
        return m;
    }

    @GetMapping("/state")
    Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings.publicSettings());
        m.put("schedules", schedules.findAllByOrderBySeqAsc());
        m.put("rules", rules.findAllByOrderBySeqAsc());
        m.put("storage", storage());
        return m;
    }

    @GetMapping("/storage")
    Map<String, Object> storageStatus() { return storage(); }

    @PostMapping("/settings")
    ResponseEntity<?> save(@RequestBody(required = false) JsonNode body) {
        JsonNode patch = body == null || !body.isObject() ? JsonNodeFactory.instance.objectNode() : body;
        Result<Map<String, Object>> r = SettingsValidator.validate(patch, settings.get());
        if (!r.ok()) return ApiExceptionHandler.bad(r);
        // chỉ ghi các khoá đã được kiểm tra (r.value) — không bao giờ ghi trực tiếp từ dữ liệu client
        settings.apply(r.value());
        if (SOURCE_KEYS.stream().anyMatch(r.value()::containsKey)) fb.resetCache();
        try { fb.listObjects(true); } catch (RuntimeException ignored) { /* lỗi kết nối hiện ở trang Kết nối */ }
        return ResponseEntity.ok(settings.publicSettings());
    }

    @PostMapping("/telegram/test")
    ResponseEntity<?> telegramTest() {
        TelegramService.Reply out = TelegramService.reply(telegram.send("✅ Kết nối Telegram thành công — Facebook Ads Auto Tool"));
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
