package com.fbads.service;

import com.fbads.common.ApiException;
import com.fbads.common.Ids;
import com.fbads.common.ValidationException;
import com.fbads.dto.Saved;
import com.fbads.dto.ScheduleRequest;
import com.fbads.engine.EngineLock;
import com.fbads.engine.ScheduleRunner;
import com.fbads.entity.Schedule;
import com.fbads.repository.ScheduleRepository;
import com.fbads.service.facebook.FacebookObjects;
import com.fbads.validation.Result;
import com.fbads.validation.ScheduleValidator;
import org.springframework.stereotype.Service;

import java.util.List;

/** Lịch tự động: xem, thêm/sửa (qua luật kiểm tra), xoá, chạy ngay. */
@Service
public class ScheduleService {
    public static final int MAX_ITEMS = 200;

    private final ScheduleRepository repo;
    private final FacebookObjects objects;
    private final ScheduleRunner runner;
    private final EngineLock lock;

    public ScheduleService(ScheduleRepository repo, FacebookObjects objects, ScheduleRunner runner, EngineLock lock) {
        this.repo = repo;
        this.objects = objects;
        this.runner = runner;
        this.lock = lock;
    }

    public List<Schedule> findAll() { return repo.findAllByOrderBySeqAsc(); }

    /** Thêm mới (chưa có id) hoặc thay thế lịch cùng id */
    public Saved<Schedule> save(ScheduleRequest input) {
        List<Schedule> all = findAll();
        Result<Schedule> r = ScheduleValidator.validate(input, objects.objectsForValidation(), all);
        if (!r.ok()) throw new ValidationException(r);
        Schedule item = r.value();
        // id chưa có trong workspace này (mục mới, hoặc id của workspace khác) → luôn cấp id mới, không bao giờ ghi đè mục của người khác
        boolean exists = item.getId() != null && !item.getId().isEmpty() && repo.existsById(item.getId());
        if (!exists && all.size() >= MAX_ITEMS) throw tooMany();
        if (!exists) item.setId(Ids.uid());
        return new Saved<>(repo.save(item), r.warnings());
    }

    public void delete(String id) { repo.deleteById(id); }

    /** Nút "Chạy ngay": chờ lượt tự động đang chạy (nếu có) rồi chạy */
    public void runNow(String id) {
        Schedule s = repo.findById(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy lịch"));
        if (lock.run(() -> runner.run(s, null)) == ScheduleRunner.RunResult.BLOCKED)
            throw new ApiException(429, "Facebook đang giới hạn số lần gọi nên lịch chưa chạy. Thử lại sau vài phút.").with("rateLimited",
                    true);
    }

    static ApiException tooMany() {
        return new ApiException(400, "Đã đạt giới hạn " + MAX_ITEMS + " mục. Hãy xoá bớt trước khi thêm mới.");
    }
}
