package com.fbads.report;

import com.fbads.notify.SendResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API gửi báo cáo ngay (ngày, tuần) qua các kênh thông báo. Việc thật nằm ở ReportService. */
@RestController
@RequestMapping("/api/report")
public class ReportController {
    private static final String NO_CHANNEL = "Chưa có kênh thông báo nào nhận Báo cáo. Thêm ở Cài đặt → Thông báo.";

    private final ReportService report;

    public ReportController(ReportService report) { this.report = report; }

    @PostMapping
    ResponseEntity<?> send() {
        SendResult.Reply out = report.send().reply(NO_CHANNEL);
        return ResponseEntity.status(out.status()).body(out.body());
    }

    @PostMapping("/weekly")
    ResponseEntity<?> sendWeekly() {
        SendResult.Reply out = report.sendWeekly().reply(NO_CHANNEL);
        return ResponseEntity.status(out.status()).body(out.body());
    }
}
