package com.fbads.engine.state;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Trạng thái của engine lưu trong DB (thay cho data.state của bản Node): khởi động lại server không mất. */
@Service
public class EngineState {
    private final ScheduleRunRepository runs;
    private final RuleMarkRepository marks;
    private final RuleResumeRepository resumes;
    private final DailyMarkRepository daily;

    public EngineState(ScheduleRunRepository runs, RuleMarkRepository marks, RuleResumeRepository resumes, DailyMarkRepository daily) {
        this.runs = runs;
        this.marks = marks;
        this.resumes = resumes;
        this.daily = daily;
    }

    // ----- Mốc lịch -----
    public boolean hasRun(String key) { return runs.existsById(key); }

    /** Giữ chỗ một mốc lịch. false = mốc này đã được chạy (bởi lượt trước hoặc bản app khác). */
    public boolean claimRun(String key, String date) {
        try {
            runs.saveAndFlush(new ScheduleRun(key, date));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    public void releaseRun(String key) { runs.deleteById(key); }

    public void cleanupRuns(String today) { runs.deleteOtherDays(today); }

    // ----- Rule: thời gian nghỉ, tạm hoãn -----
    public RuleMark mark(String ruleId, String objId) {
        return marks.findById(new RuleObjKey(ruleId, objId)).orElse(new RuleMark(new RuleObjKey(ruleId, objId)));
    }

    public void setLastRun(String ruleId, String objId, long ms) {
        RuleMark m = mark(ruleId, objId);
        m.setLastRunMs(ms);
        marks.save(m);
    }

    public void setHold(String ruleId, String objId, long untilMs) {
        RuleMark m = mark(ruleId, objId);
        m.setHoldUntilMs(untilMs);
        marks.save(m);
    }

    // ----- Rule: hẹn bật lại -----
    public List<RuleResume> resumes() { return resumes.findAll(); }

    public void addResume(String ruleId, String objId, String date) { resumes.save(new RuleResume(new RuleObjKey(ruleId, objId), date)); }

    public void removeResume(RuleObjKey key) { resumes.deleteById(key); }

    // ----- Đánh dấu theo ngày -----
    public Optional<DailyMark> daily(String day, String mark) { return daily.findById(new DailyMark.Key(day, mark)); }

    public boolean hasDaily(String day, String mark) { return daily.existsById(new DailyMark.Key(day, mark)); }

    public void putDaily(String day, String mark, Double value) { daily.save(new DailyMark(day, mark, value)); }

    public void cleanupDaily(String before) { daily.deleteBefore(before); }

    /** Xoá toàn bộ trạng thái (dùng cho kiểm thử) */
    public void clearAll() {
        runs.deleteAllInBatch();
        marks.deleteAllInBatch();
        resumes.deleteAllInBatch();
        daily.deleteAllInBatch();
    }
}
