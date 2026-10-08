package com.fbads.rule;

import com.fbads.common.Ids;
import com.fbads.common.ValidationException;
import com.fbads.dto.Saved;
import com.fbads.engine.EngineLock;
import com.fbads.schedule.ScheduleService;
import com.fbads.service.facebook.FacebookObjects;
import com.fbads.settings.SettingsService;
import com.fbads.validation.Result;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Rule tự động: xem, thêm/sửa (qua luật kiểm tra), xoá, chạy ngay, xem trước, hoạt động gần đây. */
@Service
public class RuleService {
    private final RuleRepository repo;
    private final FacebookObjects objects;
    private final SettingsService settings;
    private final RuleRunner runner;
    private final EngineLock lock;

    public RuleService(RuleRepository repo, FacebookObjects objects, SettingsService settings, RuleRunner runner, EngineLock lock) {
        this.repo = repo;
        this.objects = objects;
        this.settings = settings;
        this.runner = runner;
        this.lock = lock;
    }

    public List<Rule> findAll() { return repo.findAllByOrderBySeqAsc(); }

    private Result<Rule> validate(RuleRequest input, List<Rule> existing) {
        List<RuleValidator.Account> accounts = objects.accounts().stream()
                .map(a -> new RuleValidator.Account(a.id(), a.name())).toList();
        Result<Rule> r = RuleValidator.validate(input, objects.objectsForValidation(), existing,
                settings.get().getAccountTargets(), accounts);
        if (!r.ok()) throw new ValidationException(r);
        return r;
    }

    /** Thêm mới (chưa có id) hoặc thay thế rule cùng id */
    public Saved<Rule> save(RuleRequest input) {
        List<Rule> all = findAll();
        Result<Rule> r = validate(input, all);
        Rule item = r.value();
        // id chưa có trong workspace này (mục mới, hoặc id của workspace khác) → luôn cấp id mới, không bao giờ ghi đè mục của người khác
        boolean exists = item.getId() != null && !item.getId().isEmpty() && repo.existsById(item.getId());
        if (!exists && all.size() >= ScheduleService.MAX_ITEMS) throw ScheduleService.tooMany();
        if (!exists) item.setId(Ids.uid());
        return new Saved<>(repo.save(item), r.warnings());
    }

    public void delete(String id) { repo.deleteById(id); }

    /** Nút "Chạy rule ngay": dùng chung khoá với vòng tự động */
    public void runNow() { lock.run(() -> { runner.runRules(); return null; }); }

    /** Xem trước: rule (chưa lưu) đang khớp camp nào ngay bây giờ — không thay đổi gì */
    public RulePreview preview(RuleRequest input) {
        Result<Rule> r = validate((input == null ? RuleRequest.EMPTY : input).enabledCopy(), List.of());
        return runner.preview(r.value()).withWarnings(r.warnings());
    }

    public Map<String, RuleActivity> activity() { return runner.activity(); }
}
