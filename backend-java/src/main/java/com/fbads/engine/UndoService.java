package com.fbads.engine;

import com.fbads.common.ApiException;
import com.fbads.common.Fmt;
import com.fbads.engine.state.EngineState;
import com.fbads.facebook.AdObject;
import com.fbads.facebook.FacebookService;
import com.fbads.facebook.FbException;
import com.fbads.logs.LogEntry;
import com.fbads.logs.LogService;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hoàn tác một thay đổi đã ghi trong nhật ký (đặt camp về trạng thái/ngân sách trước đó). */
@Service
public class UndoService {
    static final int UNDO_HOLD_H = 24; // hoàn tác việc do rule làm → rule đó tạm không tác động lại camp này
    static final int UNDO_MAX_DAYS = 3;

    private final LogService logs;
    private final SettingsService settings;
    private final FacebookService fb;
    private final ActionExecutor executor;
    private final EngineState state;

    public UndoService(LogService logs, SettingsService settings, FacebookService fb, ActionExecutor executor, EngineState state) {
        this.logs = logs;
        this.settings = settings;
        this.fb = fb;
        this.executor = executor;
        this.state = state;
    }

    /** Lý do KHÔNG hoàn tác được (chuỗi rỗng = được). Như undoBlocker trong shared/validate.mjs. */
    public static String blocker(LogEntry l, long nowMs, int maxDays) {
        if (l == null) return "Không tìm thấy dòng nhật ký.";
        if (l.getUndone() != null) return "Dòng này đã được hoàn tác.";
        if ("undo".equals(l.getKind())) return "Không thể hoàn tác một lần hoàn tác.";
        if (!l.succeeded()) return "Thao tác này đã thất bại nên không có gì để hoàn tác.";
        if (Boolean.TRUE.equals(l.getSkipped())) return "Dòng này chỉ ghi nhận việc bỏ qua, không thay đổi gì.";
        if (Boolean.TRUE.equals(l.getDry())) return "Đây là bản chạy thử (chưa thay đổi thật) nên không cần hoàn tác.";
        Object type = l.getAction() == null ? null : l.getAction().get("type");
        if (!List.of("on", "off", "budget").contains(type)) return "Loại thao tác này không hoàn tác được.";
        if (l.getBefore() == null || l.getAfter() == null || l.getTarget() == null || l.getTarget().get("id") == null)
            return "Dòng nhật ký cũ không lưu đủ dữ liệu để hoàn tác.";
        if (!l.getAfter().containsKey("status") && !l.getAfter().containsKey("dailyBudget")) return "Dòng nhật ký không ghi lại thay đổi nào để hoàn tác.";
        if (l.getTs() != null && nowMs - l.getTs().toEpochMilli() > maxDays * 86_400_000L) return "Đã quá " + maxDays + " ngày, dữ liệu lúc đó có thể không còn phù hợp.";
        return "";
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    public LogEntry undo(String id, boolean force) {
        LogEntry l = logs.find(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy dòng nhật ký này."));
        String why = blocker(l, System.currentTimeMillis(), UNDO_MAX_DAYS);
        if (!why.isEmpty()) throw new ApiException(400, why);
        AppSettings s = settings.get();
        if ("mock".equals(l.getMode()) != s.isMock())
            throw new ApiException(400, "Dòng nhật ký này được ghi ở chế độ khác (Dùng thử/Thật) nên không thể hoàn tác ở chế độ hiện tại.");
        String targetId = String.valueOf(l.getTarget().get("id"));
        AdObject cur = fb.listObjects(true).stream().filter(o -> o.id.equals(targetId)).findFirst()
                .orElseThrow(() -> new ApiException(404, "Không tìm thấy camp/nhóm này trên tài khoản (có thể đã bị xoá)."));

        // Nếu sau đó có ai đổi tiếp thì hỏi lại trước khi ghi đè
        List<String> drift = new ArrayList<>();
        Map<String, Object> after = l.getAfter(), before = l.getBefore();
        if (after.containsKey("status") && !String.valueOf(after.get("status")).equals(cur.status))
            drift.add("trạng thái hiện tại là “" + ("ACTIVE".equals(cur.status) ? "Đang chạy" : "Tạm dừng") + "”");
        if (after.containsKey("dailyBudget") && cur.dailyBudget != null && Math.round(cur.dailyBudget) != Math.round(num(after.get("dailyBudget"))))
            drift.add("ngân sách hiện tại là " + Fmt.money(cur.dailyBudget));
        if (!drift.isEmpty() && !force)
            throw new ApiException(409, "Camp đã thay đổi kể từ lúc đó (" + String.join(", ", drift) + "). Hoàn tác vẫn sẽ đặt về giá trị trước đó.").with("drift", true);

        Map<String, Object> snapshot = FacebookService.snapshot(cur);
        Map<String, Object> actionJson = new LinkedHashMap<>(l.getAction());
        actionJson.put("revert", true);
        String mode = s.mode();
        Map<String, Object> newAfter = new LinkedHashMap<>();
        String detail = "";
        try {
            if (after.containsKey("status")) {
                boolean want = "ACTIVE".equals(before.get("status"));
                fb.setStatus(cur.id, want);
                newAfter.put("status", want ? "ACTIVE" : "PAUSED");
                detail = want ? "Hoàn tác: bật lại camp" : "Hoàn tác: tắt lại camp";
            }
            if (after.containsKey("dailyBudget")) {
                double b = num(before.get("dailyBudget"));
                fb.setBudget(cur.id, b);
                newAfter.put("dailyBudget", (double) Math.round(b));
                detail = "Hoàn tác: ngân sách " + Fmt.money(cur.dailyBudget == null ? 0 : cur.dailyBudget) + " → " + Fmt.money(b);
            }
        } catch (RuntimeException ex) {
            executor.record(false, e -> {
                e.setKind("undo"); e.setSource("Hoàn tác"); e.setName(l.getName()); e.setRefLogId(l.getId()); e.setTarget(l.getTarget()); e.setMode(mode);
                e.setBefore(snapshot); e.setAction(actionJson); e.setDetail(ex.getMessage()); e.setOk(false); e.setError(FbException.describe(ex));
            });
            throw ex;
        }
        String fDetail = detail;
        LogEntry entry = executor.record(false, e -> {
            e.setKind("undo"); e.setSource("Hoàn tác"); e.setName(l.getName()); e.setRefLogId(l.getId()); e.setTarget(l.getTarget()); e.setMode(mode);
            e.setBefore(snapshot); e.setAction(actionJson); e.setDetail(fDetail); e.setOk(true); e.setAfter(newAfter);
        });
        l.setUndone(Map.of("at", entry.getTs().toString(), "logId", entry.getId()));
        logs.save(l);
        // Việc gốc do rule làm: tạm hoãn rule đó với camp này để nó không làm lại ngay ở lần kiểm tra sau
        if ("rule".equals(l.getKind()) && l.getRefId() != null)
            state.setHold(l.getRefId(), targetId, System.currentTimeMillis() + UNDO_HOLD_H * 3_600_000L);
        return entry;
    }
}
