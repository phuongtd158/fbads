package com.fbads.schedule;

import com.fbads.dto.Responses.Ok;
import com.fbads.dto.Saved;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API lịch tự động: lưu, xoá, chạy ngay. Việc thật nằm ở ScheduleService. */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService schedules;

    public ScheduleController(ScheduleService schedules) { this.schedules = schedules; }

    @PostMapping
    Saved<Schedule> save(@RequestBody(required = false) ScheduleRequest b) { return schedules.save(b); }

    @DeleteMapping("/{id}")
    Ok delete(@PathVariable String id) {
        schedules.delete(id);
        return Ok.OK;
    }

    @PostMapping("/{id}/run")
    Ok run(@PathVariable String id) {
        schedules.runNow(id);
        return Ok.OK;
    }
}
