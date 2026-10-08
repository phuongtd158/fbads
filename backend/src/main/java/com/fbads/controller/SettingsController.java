package com.fbads.controller;

import com.fbads.dto.Responses.State;
import com.fbads.dto.Responses.Storage;
import com.fbads.dto.SettingsPatch;
import com.fbads.notify.Notice;
import com.fbads.notify.Notifier;
import com.fbads.notify.SendResult;
import com.fbads.service.RuleService;
import com.fbads.service.ScheduleService;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookObjects;
import com.fbads.service.facebook.FacebookState;
import com.fbads.validation.Result;
import com.fbads.validation.SettingsValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Trạng thái chung, cài đặt và thử kết nối Telegram. */
@RestController
@RequestMapping("/api")
public class SettingsController {
    /** Đổi nguồn dữ liệu → bỏ cache cũ */
    private static final List<String> SOURCE_KEYS = List.of("mock", "adAccountId", "adAccountIds", "accessToken");

    private final SettingsService settings;
    private final ScheduleService schedules;
    private final RuleService rules;
    private final FacebookObjects objects;
    private final FacebookState fbState;
    private final Notifier notifier;

    public SettingsController(SettingsService settings, ScheduleService schedules, RuleService rules,
            FacebookObjects objects, FacebookState fbState, Notifier notifier) {
        this.settings = settings;
        this.schedules = schedules;
        this.rules = rules;
        this.objects = objects;
        this.fbState = fbState;
        this.notifier = notifier;
    }

    @GetMapping("/state")
    State state() {
        return new State(settings.publicSettings(), schedules.findAll(), rules.findAll(), Storage.MYSQL);
    }

    /** Nơi lưu dữ liệu (hiện ở Cài đặt → Chung) */
    @GetMapping("/storage")
    Storage storageStatus() {
        return Storage.MYSQL;
    }

    @PostMapping("/settings")
    ResponseEntity<?> save(@RequestBody(required = false) SettingsPatch body) {
        Result<Map<String, Object>> r = SettingsValidator.validate(body == null ? new SettingsPatch() : body, settings.get());
        if (!r.ok()) return ApiExceptionHandler.bad(r);
        // chỉ ghi các khoá đã được kiểm tra (r.value) — không bao giờ ghi trực tiếp từ dữ liệu client
        settings.apply(r.value());
        if (SOURCE_KEYS.stream().anyMatch(r.value()::containsKey)) fbState.resetCache();
        try { objects.listObjects(true); } catch (RuntimeException ignored) { /* lỗi kết nối hiện ở trang Kết nối */ }
        return ResponseEntity.ok(settings.publicSettings());
    }

    @PostMapping("/telegram/test")
    ResponseEntity<?> telegramTest() {
        SendResult.Reply out = notifier.sendNow(new Notice(Notice.Topic.ALERT, "✅ Kết nối Telegram thành công — Facebook Ads Auto Tool"))
                .reply("Cần nhập Bot Token và Chat ID trước.");
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
