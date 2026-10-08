package com.fbads.service;

import com.fbads.common.ApiException;
import com.fbads.common.Fmt;
import com.fbads.dto.AdObject;
import com.fbads.engine.ActionExecutor;
import com.fbads.entity.AppSettings;
import com.fbads.entity.LogChange;
import com.fbads.entity.LogEntry;
import com.fbads.entity.LogError;
import com.fbads.entity.LogKind;
import com.fbads.entity.LogSnapshot;
import com.fbads.entity.LogUndone;
import com.fbads.service.facebook.FacebookActions;
import com.fbads.service.facebook.FacebookObjects;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Hoàn tác một thay đổi đã ghi trong nhật ký (đặt camp về trạng thái/ngân sách trước đó). */
@Service
public class UndoService {
    static final int UNDO_HOLD_H = 24; // hoàn tác việc do rule làm → rule đó tạm không tác động lại camp này
    static final int UNDO_MAX_DAYS = 3;

    private final LogService logs;
    private final SettingsService settings;
    private final FacebookObjects objects;
    private final FacebookActions actions;
    private final ActionExecutor executor;
    private final EngineState state;

    public UndoService(LogService logs, SettingsService settings, FacebookObjects objects, FacebookActions actions,
            ActionExecutor executor, EngineState state) {
        this.logs = logs;
        this.settings = settings;
        this.objects = objects;
        this.actions = actions;
        this.executor = executor;
        this.state = state;
    }

    /** Lý do KHÔNG hoàn tác được (chuỗi rỗng = được). Như undoBlocker trong shared/validate.mjs. */
    public static String blocker(LogEntry l, long nowMs, int maxDays) {
        if (l == null) return "Không tìm thấy dòng nhật ký.";
        if (l.getUndone() != null) return "Dòng này đã được hoàn tác.";
        if (l.getKind() == LogKind.UNDO) return "Không thể hoàn tác một lần hoàn tác.";
        if (!l.succeeded()) return "Thao tác này đã thất bại nên không có gì để hoàn tác.";
        if (Boolean.TRUE.equals(l.getSkipped())) return "Dòng này chỉ ghi nhận việc bỏ qua, không thay đổi gì.";
        if (Boolean.TRUE.equals(l.getDry())) return "Đây là bản chạy thử (chưa thay đổi thật) nên không cần hoàn tác.";
        if (l.getAction() == null || !l.getAction().changesObject()) return "Loại thao tác này không hoàn tác được.";
        if (l.getBefore() == null || l.getAfter() == null || l.getTarget() == null || l.getTarget().id() == null)
            return "Dòng nhật ký cũ không lưu đủ dữ liệu để hoàn tác.";
        if (!l.getAfter().changesStatus() && !l.getAfter().changesBudget())
            return "Dòng nhật ký không ghi lại thay đổi nào để hoàn tác.";
        if (l.getTs() != null && nowMs - l.getTs().toEpochMilli() > maxDays * 86_400_000L)
            return "Đã quá " + maxDays + " ngày, dữ liệu lúc đó có thể không còn phù hợp.";
        return "";
    }

    /** Dòng nhật ký "Hoàn tác" cho dòng l (chưa có kết quả) */
    private static LogEntry undoEntry(LogEntry l, String mode, LogSnapshot snapshot) {
        LogEntry e = LogEntry.of(LogKind.UNDO, "Hoàn tác", l.getName());
        e.setRefLogId(l.getId());
        e.setTarget(l.getTarget());
        e.setMode(mode);
        e.setBefore(snapshot);
        e.setAction(l.getAction().reverted());
        return e;
    }

    public LogEntry undo(String id, boolean force) {
        LogEntry l = logs.find(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy dòng nhật ký này."));
        String why = blocker(l, System.currentTimeMillis(), UNDO_MAX_DAYS);
        if (!why.isEmpty()) throw new ApiException(400, why);
        AppSettings s = settings.get();
        if ("mock".equals(l.getMode()) != s.isMock())
            throw new ApiException(400, "Dòng nhật ký này được ghi ở chế độ khác (Dùng thử/Thật) nên không thể hoàn tác "
                    + "ở chế độ hiện tại.");
        String targetId = l.getTarget().id();
        AdObject cur = objects.listObjects(true).stream().filter(o -> o.id().equals(targetId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "Không tìm thấy camp/nhóm này trên tài khoản (có thể đã bị xoá)."));

        // Nếu sau đó có ai đổi tiếp thì hỏi lại trước khi ghi đè
        List<String> drift = new ArrayList<>();
        LogChange after = l.getAfter();
        LogSnapshot before = l.getBefore();
        if (after.changesStatus() && !after.status().equals(cur.status()))
            drift.add("trạng thái hiện tại là “" + ("ACTIVE".equals(cur.status()) ? "Đang chạy" : "Tạm dừng") + "”");
        if (after.changesBudget() && cur.dailyBudget() != null && Math.round(cur.dailyBudget()) != Math.round(after.dailyBudget()))
            drift.add("ngân sách hiện tại là " + Fmt.money(cur.dailyBudget()));
        if (!drift.isEmpty() && !force)
            throw new ApiException(409, "Camp đã thay đổi kể từ lúc đó (" + String.join(", ", drift)
                    + "). Hoàn tác vẫn sẽ đặt về giá trị trước đó.").withDrift();

        LogSnapshot snapshot = LogSnapshot.of(cur);
        String mode = s.mode();
        LogChange newAfter = null;
        String detail = "";
        try {
            if (after.changesStatus()) {
                boolean want = before.wasActive();
                actions.setStatus(cur.id(), want);
                newAfter = LogChange.status(want);
                detail = want ? "Hoàn tác: bật lại camp" : "Hoàn tác: tắt lại camp";
            }
            if (after.changesBudget()) {
                double b = before.dailyBudget() == null ? 0 : before.dailyBudget();
                actions.setBudget(cur.id(), b);
                newAfter = LogChange.budget(Math.round(b));
                detail = "Hoàn tác: ngân sách " + Fmt.money(cur.dailyBudget() == null ? 0 : cur.dailyBudget()) + " → " + Fmt.money(b);
            }
        } catch (RuntimeException ex) {
            LogEntry e = undoEntry(l, mode, snapshot);
            e.setDetail(ex.getMessage());
            e.setOk(false);
            e.setError(LogError.of(ex));
            executor.record(e, false);
            throw ex;
        }
        LogEntry e = undoEntry(l, mode, snapshot);
        e.setDetail(detail);
        e.setOk(true);
        e.setAfter(newAfter);
        LogEntry entry = executor.record(e, false);
        l.setUndone(LogUndone.by(entry));
        logs.save(l);
        // Việc gốc do rule làm: tạm hoãn rule đó với camp này để nó không làm lại ngay ở lần kiểm tra sau
        if (l.getKind() == LogKind.RULE && l.getRefId() != null)
            state.setHold(l.getRefId(), targetId, System.currentTimeMillis() + UNDO_HOLD_H * 3_600_000L);
        return entry;
    }
}
