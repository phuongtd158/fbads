package com.fbads.service.facebook;

import com.fbads.dto.AdObject;
import com.fbads.event.AppEvent;
import com.fbads.event.EventBus;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.fbads.service.facebook.FacebookParse.offsetOf;

/**
 * Thay đổi trên Facebook: bật/tắt, đổi ngân sách ngày. Chỉ gọi Facebook (hoặc dữ liệu giả), không ghi nhật ký:
 * nhật ký và kiểm tra trước khi đổi nằm ở ObjectService / engine.ActionExecutor.
 * <p>
 * Sau khi đổi, số đã tải coi như cũ: lần đọc sau tải lại từ Facebook. Kể cả khi lỗi/hết giờ (không rõ Facebook đã
 * đổi chưa) cũng vậy, để lần sau thấy trạng thái thật.
 */
@Service
public class FacebookActions {
    private final FacebookGraph graph;
    private final FacebookState state;
    private final FacebookObjects objects;
    private final EventBus events;

    public FacebookActions(FacebookGraph graph, FacebookState state, FacebookObjects objects, EventBus events) {
        this.graph = graph;
        this.state = state;
        this.objects = objects;
        this.events = events;
    }

    public void setStatus(String id, boolean on) {
        if (state.isMock()) {
            state.ws().mock.setStatus(id, on);
        } else {
            try {
                graph.call("POST", id, Map.of("status", on ? "ACTIVE" : "PAUSED"), null);
            } finally {
                state.expireObjects();
            }
        }
        changed(id);
    }

    public void setBudget(String id, double amount) {
        long rounded = Math.round(amount);
        if (state.isMock()) {
            state.ws().mock.setBudget(id, rounded);
        } else {
            AdObject o = objects.findCached(id);
            String cur = o != null && o.currency() != null ? o.currency() : state.ws().currency;
            try {
                graph.call("POST", id, Map.of("daily_budget", Long.toString(Math.round(rounded * offsetOf(cur)))), null);
            } finally {
                state.expireObjects();
            }
        }
        changed(id);
    }

    /** Số ở Redis đã cũ → xoá; báo giao diện tải lại */
    private void changed(String id) {
        state.expireObjects();
        state.evictL2(false);
        events.publish(AppEvent.OBJECTS_CHANGED, id, false, Map.of("id", id));
    }
}
