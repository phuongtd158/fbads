package com.fbads.controller;

import com.fbads.dto.Requests.NotifyTargetSave;
import com.fbads.dto.Responses.NotifyOverview;
import com.fbads.dto.Responses.NotifyTargetView;
import com.fbads.dto.Responses.Ok;
import com.fbads.notify.Notifier;
import com.fbads.notify.NotifyTargetService;
import com.fbads.notify.SendResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Kênh thông báo của workspace (Cài đặt → Thông báo): xem, thêm, sửa, xoá, gửi thử. Ghi cần quyền OWNER (WorkspaceFilter). */
@RestController
@RequestMapping("/api/notify")
public class NotifyController {
    private final NotifyTargetService targets;
    private final Notifier notifier;

    public NotifyController(NotifyTargetService targets, Notifier notifier) {
        this.targets = targets;
        this.notifier = notifier;
    }

    @GetMapping
    NotifyOverview overview() {
        return targets.overview();
    }

    @PostMapping("/channels")
    NotifyTargetView create(@RequestBody(required = false) NotifyTargetSave body) {
        return targets.view(targets.save(null, body));
    }

    @PostMapping("/channels/{id}")
    NotifyTargetView update(@PathVariable String id, @RequestBody(required = false) NotifyTargetSave body) {
        return targets.view(targets.save(id, body));
    }

    @DeleteMapping("/channels/{id}")
    Ok delete(@PathVariable String id) {
        targets.delete(id);
        return Ok.OK;
    }

    /** Gửi một tin thử tới kênh này: 200 nếu ít nhất một người nhận được, 400 kèm lý do từng người nếu không ai nhận được */
    @PostMapping("/channels/{id}/test")
    ResponseEntity<?> test(@PathVariable String id) {
        SendResult.Reply out = notifier.test(id).reply("Kênh này chưa đủ cấu hình để gửi.");
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
