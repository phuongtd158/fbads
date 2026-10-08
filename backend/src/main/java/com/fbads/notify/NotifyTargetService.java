package com.fbads.notify;

import com.fbads.common.ApiException;
import com.fbads.common.Ids;
import com.fbads.common.ValidationException;
import com.fbads.dto.Requests.NotifyTargetSave;
import com.fbads.dto.Responses.NotifyOverview;
import com.fbads.dto.Responses.NotifyTargetView;
import com.fbads.dto.Responses.NotifyTopic;
import com.fbads.dto.Responses.NotifyType;
import com.fbads.entity.NotifyTarget;
import com.fbads.repository.NotifyTargetRepository;
import com.fbads.validation.Result;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Thêm / sửa / xoá kênh thông báo của workspace hiện tại (bảng notify_targets, tự lọc theo workspace).
 * Phần riêng của từng loại kênh (ô nào bắt buộc, đúng dạng chưa) do chính lớp kênh kiểm tra: NotifyChannel.validate.
 */
@Service
public class NotifyTargetService {
    /** Mặc định kênh mới nhận mọi loại tin */
    static final List<String> ALL_TOPICS = Arrays.stream(Notice.Topic.values()).map(Enum::name).toList();

    private final NotifyTargetRepository repo;
    /** Các loại kênh có trong code, theo mã, đúng thứ tự Spring tìm thấy */
    private final Map<String, NotifyChannel> channels;
    private final JsonMapper json;

    public NotifyTargetService(NotifyTargetRepository repo, List<NotifyChannel> channels, JsonMapper json) {
        this.repo = repo;
        this.channels = channels.stream().collect(Collectors.toMap(NotifyChannel::type, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
        this.json = json;
    }

    public List<NotifyTarget> all() { return repo.findAllByOrderBySeqAsc(); }

    public NotifyTarget get(String id) {
        return repo.findById(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy kênh thông báo này."));
    }

    /** Lớp kênh của một kênh đã cài; null nếu loại kênh không còn trong code */
    public NotifyChannel channelOf(NotifyTarget t) { return channels.get(t.getType()); }

    /** Cấu hình của kênh (đã giải mã) */
    public ObjectNode config(NotifyTarget t) {
        return t.getConfig() == null || t.getConfig().isBlank() ? json.createObjectNode() : (ObjectNode) json.readTree(t.getConfig());
    }

    /** Tên hiện trong kết quả gửi: tên người dùng đặt, chưa đặt thì tên loại kênh */
    public String displayName(NotifyTarget t) {
        if (t.getName() != null && !t.getName().isBlank()) return t.getName();
        NotifyChannel c = channelOf(t);
        return c == null ? t.getType() : c.label();
    }

    // ------------------------------------------------------------------ Cho giao diện

    public NotifyOverview overview() {
        List<NotifyType> types = channels.values().stream()
                .map(c -> new NotifyType(c.type(), c.label(), c.fields(), c.help(),
                        c.defaultTopics().stream().map(Enum::name).toList())).toList();
        List<NotifyTopic> topics = Arrays.stream(Notice.Topic.values()).map(t -> new NotifyTopic(t.name(), t.label)).toList();
        return new NotifyOverview(types, topics, all().stream().map(this::view).toList());
    }

    /** Kênh đã cài → JSON cho giao diện: bỏ giá trị các ô bí mật, chỉ cho biết đã có hay chưa */
    public NotifyTargetView view(NotifyTarget t) {
        NotifyChannel c = channelOf(t);
        ObjectNode cfg = config(t);
        List<String> saved = new ArrayList<>();
        if (c != null) {
            for (ConfigField f : c.fields()) {
                if (!f.secret()) continue;
                if (!cfg.path(f.key()).asString("").isEmpty()) saved.add(f.key());
                cfg.put(f.key(), "");
            }
        }
        return new NotifyTargetView(t.getId(), t.getType(), c == null ? t.getType() : c.label(), t.getName(), t.isEnabled(),
                t.getTopics(), cfg, saved);
    }

    // ------------------------------------------------------------------ Thêm / sửa / xoá

    /**
     * Thêm (id null) hoặc sửa một kênh. Ô bí mật để trống khi sửa = giữ giá trị cũ.
     * Sai thì ném ValidationException: 400 { error, errors: { "config.token": "…", "topics": "…" } }.
     */
    public NotifyTarget save(String id, NotifyTargetSave body) {
        NotifyTargetSave b = body == null ? NotifyTargetSave.EMPTY : body;
        NotifyTarget t = id == null ? null : get(id);
        Result.Collector e = new Result.Collector();

        String type = t != null ? t.getType() : (b.type() == null ? "" : b.type().trim());
        NotifyChannel channel = channels.get(type);
        if (channel == null) throw new ValidationException(error("type", "Chưa chọn loại kênh, hoặc loại kênh không hỗ trợ."));

        String name = b.name() == null ? (t != null ? t.getName() : "") : b.name().trim();
        if (name.length() > 100) e.err("name", "Tên tối đa 100 ký tự");

        List<String> topics = b.topics() != null ? b.topics()
                : t != null ? t.getTopics() : channel.defaultTopics().stream().map(Enum::name).toList();
        if (!ALL_TOPICS.containsAll(topics)) e.err("topics", "Loại tin không hợp lệ");
        else if (topics.isEmpty()) e.err("topics", "Chọn ít nhất một loại tin để kênh nhận");

        // không gửi config (vd chỉ bật/tắt kênh) = giữ nguyên cấu hình cũ
        ObjectNode cfg = b.config() != null ? b.config().deepCopy() : t != null ? config(t) : json.createObjectNode();
        if (t != null) keepOldSecrets(channel, cfg, config(t));
        channel.validate(cfg).forEach((field, msg) -> e.err("config." + field, msg));

        if (!e.empty()) throw new ValidationException(e.done(null));
        if (t == null) t = new NotifyTarget(Ids.uid(), type);
        t.setName(name);
        if (b.enabled() != null) t.setEnabled(b.enabled());
        t.setTopics(ALL_TOPICS.stream().filter(topics::contains).toList()); // bỏ trùng, giữ thứ tự cố định
        t.setConfig(json.writeValueAsString(cfg));
        return repo.save(t);
    }

    public void delete(String id) { repo.delete(get(id)); }

    /** Ô bí mật không gửi lên (hoặc để trống) → lấy lại giá trị đã lưu */
    private static void keepOldSecrets(NotifyChannel channel, ObjectNode cfg, ObjectNode old) {
        for (ConfigField f : channel.fields()) {
            if (f.secret() && cfg.path(f.key()).asString("").isBlank()) cfg.put(f.key(), old.path(f.key()).asString(""));
        }
    }

    private static Result<Void> error(String field, String msg) {
        Result.Collector e = new Result.Collector();
        e.err(field, msg);
        return e.done(null);
    }
}
