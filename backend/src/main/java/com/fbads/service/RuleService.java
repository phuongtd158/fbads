package com.fbads.service;

import com.fbads.common.Ids;
import com.fbads.common.ValidationException;
import com.fbads.dto.Saved;
import com.fbads.engine.EngineLock;
import com.fbads.engine.RuleRunner;
import com.fbads.entity.Rule;
import com.fbads.repository.RuleRepository;
import com.fbads.validation.Result;
import com.fbads.validation.RuleValidator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rule tự động: xem, thêm/sửa (qua luật kiểm tra), xoá, chạy ngay, xem trước, hoạt động gần đây. */
@Service
public class RuleService {
    private final RuleRepository repo;
    private final FacebookService fb;
    private final SettingsService settings;
    private final RuleRunner runner;
    private final EngineLock lock;

    public RuleService(RuleRepository repo, FacebookService fb, SettingsService settings, RuleRunner runner, EngineLock lock) {
        this.repo = repo;
        this.fb = fb;
        this.settings = settings;
        this.runner = runner;
        this.lock = lock;
    }

    public List<Rule> findAll() { return repo.findAllByOrderBySeqAsc(); }

    private Result<Rule> validate(JsonNode input, List<Rule> existing) {
        List<RuleValidator.Account> accounts = fb.accounts().stream()
                .map(a -> new RuleValidator.Account((String) a.get("id"), (String) a.get("name"))).toList();
        Result<Rule> r = RuleValidator.validate(input, fb.objectsForValidation(), existing, settings.get().getAccountTargets(), accounts);
        if (!r.ok()) throw new ValidationException(r);
        return r;
    }

    /** Thêm mới (chưa có id) hoặc thay thế rule cùng id */
    public Saved<Rule> save(JsonNode input) {
        List<Rule> all = findAll();
        Result<Rule> r = validate(input, all);
        Rule item = r.value();
        boolean hasId = item.getId() != null && !item.getId().isEmpty();
        if ((!hasId || !repo.existsById(item.getId())) && all.size() >= ScheduleService.MAX_ITEMS) throw ScheduleService.tooMany();
        if (!hasId) item.setId(Ids.uid());
        return new Saved<>(repo.save(item), r.warnings());
    }

    public void delete(String id) { repo.deleteById(id); }

    /** Nút "Chạy rule ngay": dùng chung khoá với vòng tự động */
    public void runNow() { lock.run(() -> { runner.runRules(); return null; }); }

    /** Xem trước: rule (chưa lưu) đang khớp camp nào ngay bây giờ — không thay đổi gì */
    public Map<String, Object> preview(ObjectNode input) {
        ObjectNode copy = input.deepCopy();
        copy.put("enabled", true);
        Result<Rule> r = validate(copy, List.of());
        Map<String, Object> out = new LinkedHashMap<>(runner.preview(r.value()));
        out.put("warnings", r.warnings());
        return out;
    }

    public Map<String, Object> activity() { return runner.activity(); }
}
