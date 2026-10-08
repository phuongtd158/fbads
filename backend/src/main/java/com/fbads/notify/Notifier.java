package com.fbads.notify;

import com.fbads.entity.NotifyTarget;
import com.fbads.event.AppEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Phòng thư: nhận sự kiện, soạn thông báo, giao cho từng kênh của workspace.
 * <pre>
 * sự kiện tới
 *   → noticeOf(e): sự kiện này có cần báo không, nội dung gì?
 *   → targets(loại tin): các kênh đang bật của workspace có nhận loại tin này (bảng notify_targets)
 *   → với từng kênh: gửi (handleOnce: kênh nào đã gửi rồi thì bỏ qua)
 * </pre>
 * Kênh nào gửi không được thì ném lỗi sau khi đã thử hết các kênh, để nơi gọi quyết định: Kafka thử lại (lỗi tạm thời)
 * hoặc đưa vào DLT (lỗi cố định); chế độ không Kafka thì chỉ ghi log.
 */
@Service
public class Notifier {
    /** Dấu "đã gửi" của từng sự kiện, từng kênh trong Redis: fbads:notify:{sự kiện}:{kênh}, giữ 2 ngày */
    static final String SENT_PREFIX = "fbads:notify:";
    private static final Duration SENT_KEEP = Duration.ofDays(2);
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    /**
     * Một kênh đã cài, sẵn sàng gửi: lớp kênh (biết cách gửi) + cấu hình của workspace.
     *
     * @param id   mã kênh đã cài (NotifyTarget.id), dùng làm dấu "đã gửi"
     * @param name tên hiện trong kết quả gửi
     */
    record Target(String id, String name, NotifyChannel channel, JsonNode config) {
        SendResult send(Notice n) { return channel.send(n, config).named(name); }
    }

    private final NotifyTargetService targets;
    private final StringRedisTemplate redis;

    public Notifier(NotifyTargetService targets, StringRedisTemplate redis) {
        this.targets = targets;
        this.redis = redis;
    }

    /** Các kênh đang bật của workspace hiện tại có nhận loại tin này */
    List<Target> targets(Notice.Topic topic) {
        List<Target> out = new ArrayList<>();
        for (NotifyTarget t : targets.all()) {
            NotifyChannel channel = targets.channelOf(t);
            if (!t.isEnabled() || channel == null || !t.getTopics().contains(topic.name())) continue;
            out.add(new Target(t.getId(), targets.displayName(t), channel, targets.config(t)));
        }
        return out;
    }

    /** Nút "Gửi thử" của một kênh: gửi dù kênh đang tắt hay không nhận loại tin này */
    public SendResult test(String id) {
        NotifyTarget t = targets.get(id);
        NotifyChannel channel = targets.channelOf(t);
        if (channel == null) return SendResult.notConfigured();
        Notice n = new Notice(Notice.Topic.ALERT, "✅ <b>Kết nối " + channel.label() + " thành công</b>\nFacebook Ads Auto Tool sẽ gửi thông báo tới đây.");
        return new Target(t.getId(), targets.displayName(t), channel, targets.config(t)).send(n);
    }

    /** Workspace có kênh nào nhận loại tin này không (vd chưa cài kênh nào thì khỏi soạn báo cáo) */
    public boolean hasChannelFor(Notice.Topic topic) { return !targets(topic).isEmpty(); }

    /** Gửi ngay cho các kênh nhận loại tin này (nút "Gửi báo cáo ngay"): trả kết quả từng người nhận, không ném lỗi */
    public SendResult sendNow(Notice n) {
        List<SendResult> all = new ArrayList<>();
        for (Target t : targets(n.topic())) all.add(t.send(n));
        return SendResult.merge(all);
    }

    /** Chế độ không Kafka: gửi cho mọi kênh, không chống trùng (không có ai giao lại sự kiện) */
    public void handle(AppEvent e) {
        Notice n = noticeOf(e);
        if (n == null) return;
        deliver(n, t -> true, t -> { });
    }

    /**
     * Như handle(), nhưng mỗi kênh chỉ nhận một lần dù Kafka giao lại sự kiện (tool chết trước khi kịp báo đã đọc,
     * hoặc kênh khác lỗi nên cả sự kiện được thử lại).
     * Đặt dấu "đã gửi" trong Redis trước khi gửi (SET NX); gửi lỗi thì xoá dấu để lượt thử lại gửi được.
     * Redis lỗi thì vẫn gửi: thà trùng tin còn hơn mất tin.
     */
    public void handleOnce(AppEvent e) {
        Notice n = noticeOf(e);
        if (n == null) return;
        deliver(n, t -> claim(key(e, t)), t -> release(key(e, t)));
    }

    /**
     * Giao thông báo cho từng kênh. Kênh lỗi không chặn kênh sau; thử hết rồi mới ném lỗi.
     *
     * @param claim   trước khi gửi: false = kênh này đã nhận rồi, bỏ qua
     * @param onError gửi lỗi: gỡ dấu "đã gửi" để lượt sau thử lại
     */
    private void deliver(Notice n, Predicate<Target> claim, Consumer<Target> onError) {
        NotifyFailure firstRetryable = null, firstPermanent = null;
        for (Target t : targets(n.topic())) {
            if (!claim.test(t)) {
                log.info("Bỏ qua kênh {}: đã gửi thông báo này rồi", t.name());
                continue;
            }
            try {
                t.send(n).throwIfNobodyGotIt();
            } catch (NotifyFailure.Retryable ex) {
                onError.accept(t);
                if (firstRetryable == null) firstRetryable = ex;
            } catch (NotifyFailure.Permanent ex) {
                onError.accept(t);
                if (firstPermanent == null) firstPermanent = ex;
            }
        }
        // còn kênh lỗi tạm thời thì thử lại cả sự kiện (kênh đã gửi được sẽ bỏ qua nhờ dấu "đã gửi")
        if (firstRetryable != null) throw firstRetryable;
        if (firstPermanent != null) throw firstPermanent;
    }

    private static String key(AppEvent e, Target t) { return SENT_PREFIX + e.id() + ":" + t.id(); }

    private boolean claim(String key) {
        try {
            return !Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(key, "1", SENT_KEEP));
        } catch (RuntimeException ex) {
            log.warn("Không kiểm tra được thông báo đã gửi chưa (Redis lỗi), vẫn gửi: {}", ex.getMessage());
            return true;
        }
    }

    private void release(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Không xoá được dấu đã gửi {} (Redis lỗi), lượt thử lại sẽ bỏ qua kênh này: {}", key, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ Soạn thông báo từ sự kiện

    /**
     * Thông báo cho sự kiện, null = sự kiện này không cần báo.
     *  - log.created có cờ báo (lịch, rule, dừng khẩn, hoàn tác): một dòng tóm tắt;
     *  - report.daily: báo cáo hằng ngày;
     *  - notice: thông báo soạn sẵn (cảnh báo, báo cáo tuần, báo cáo công ty…), loại tin nằm trong data.topic.
     */
    public static Notice noticeOf(AppEvent e) {
        if (!e.shouldNotify() || e.data() == null) return null;
        return switch (e.type()) {
            case AppEvent.LOG_CREATED -> new Notice(Notice.Topic.LOG, logLine(e.data()));
            case AppEvent.DAILY_REPORT -> text(Notice.Topic.REPORT, e.data());
            case AppEvent.NOTICE, AppEvent.LEGACY_TELEGRAM_TEXT -> text(topicOf(e.data()), e.data());
            default -> null;
        };
    }

    private static Notice text(Notice.Topic topic, JsonNode data) {
        String t = data.path("text").asString(null);
        return t == null ? null : new Notice(topic, t);
    }

    /** data.topic = tên Notice.Topic; thiếu (bản ghi cũ) hoặc sai thì coi là cảnh báo */
    private static Notice.Topic topicOf(JsonNode data) {
        try {
            return Notice.Topic.valueOf(data.path("topic").asString("ALERT"));
        } catch (IllegalArgumentException ex) {
            return Notice.Topic.ALERT;
        }
    }

    /** "✅ <b>Nguồn</b>\nTên: chi tiết" — icon: ❌ lỗi, 🔔 cảnh báo, ⏭️ bỏ qua, 🧪 chạy thử, ✅ thành công */
    static String logLine(JsonNode l) {
        boolean ok = !l.path("ok").isBoolean() || l.path("ok").asBoolean();
        boolean isNotify = "notify".equals(l.path("action").path("type").asString(null));
        boolean dry = l.path("dry").asBoolean(false);
        boolean skipped = l.path("skipped").asBoolean(false);
        String icon = !ok ? "❌" : isNotify ? "🔔" : skipped ? "⏭️" : dry ? "🧪" : "✅";
        String tag = dry && !isNotify ? " (chạy thử)" : "";
        return icon + " <b>" + str(l, "source") + "</b>" + tag + "\n" + str(l, "name") + ": " + str(l, "detail");
    }

    /** Như nối chuỗi Java trước đây: trường trống in ra "null" */
    private static String str(JsonNode l, String field) {
        JsonNode v = l.get(field);
        return v == null || v.isNull() ? "null" : v.asString();
    }
}
