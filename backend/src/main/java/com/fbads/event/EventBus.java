package com.fbads.event;

import com.fbads.engine.EngineClock;
import com.fbads.notify.Notice;
import com.fbads.security.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Nơi duy nhất phát sự kiện. Nơi phát (nhật ký, FacebookActions, engine) không biết ai nhận và nhận bằng cách nào.
 * Sự kiện mang workspace của luồng đang phát (WorkspaceContext).
 * Đang trong transaction thì chờ commit xong mới phát, để consumer không thấy dữ liệu có thể còn bị huỷ.
 */
@Service
public class EventBus {
    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final EventTransport transport;
    private final JsonMapper json;
    private final EngineClock clock;

    public EventBus(EventTransport transport, JsonMapper json, EngineClock clock) {
        this.transport = transport;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param type   loại sự kiện (AppEvent.LOG_CREATED…)
     * @param key    khoá phân vùng: sự kiện cùng khoá được nhận theo đúng thứ tự phát (tự thêm tiền tố workspace: "{id}:key")
     * @param notify gửi thông báo cho sự kiện này (Telegram, Gmail… tuỳ kênh workspace đã cài)
     * @param data   nội dung, chuyển sang JSON ngay lúc phát (đối tượng gốc có thể bị sửa sau đó)
     */
    public void publish(String type, String key, boolean notify, Object data) {
        Long ws = WorkspaceContext.current();
        AppEvent e = new AppEvent(UUID.randomUUID().toString(), type, ws == null ? key : ws + ":" + key, clock.millis(),
                notify, json.valueToTree(data), ws);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send(e); }
            });
        } else {
            send(e);
        }
    }

    /**
     * Phát một thông báo soạn sẵn. Nơi phát không biết thông báo đi kênh nào: Notifier chọn theo cài đặt của workspace.
     *
     * @param key khoá phân vùng: thông báo cùng khoá đến theo đúng thứ tự phát
     */
    public void notify(String key, Notice notice) {
        publish(AppEvent.NOTICE, key, true, Map.of("topic", notice.topic().name(), "text", notice.html()));
    }

    /** Gửi lỗi (Kafka không chạy…) chỉ ghi log: dữ liệu đã nằm trong DB, mất sự kiện thì chỉ mất thông báo/cập nhật tức thì */
    private void send(AppEvent e) {
        try {
            transport.send(e);
        } catch (RuntimeException ex) {
            log.warn("Không phát được sự kiện {} ({}): {}", e.type(), e.id(), ex.getMessage());
        }
    }
}
