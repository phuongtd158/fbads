package com.fbads.validation;

import com.fbads.automation.Schedule;
import com.fbads.common.Fmt;
import com.fbads.common.Json;
import com.fbads.engine.ScheduleRunner;
import com.fbads.facebook.AdObject;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Kiểm tra lịch trước khi lưu (bản Java của validateSchedule trong shared/validate.mjs).
 * Đây là luật nghiệp vụ nhiều trường liên quan nhau (giờ ↔ hành động ↔ camp ↔ các lịch khác) nên viết thành lớp riêng
 * thay vì chú thích Bean Validation trên từng trường; câu báo lỗi giữ y hệt để giao diện hiện đúng chỗ.
 */
public final class ScheduleValidator {
    private static final List<String> ACTIONS = List.of("on", "off", "budget", "window");
    private static final List<String> FILTER_STATUSES = List.of("all", "running", "off");
    private static final List<String> FILTER_OPS = List.of("any", "lt", "lte", "gt", "gte", "between");

    private ScheduleValidator() {}

    static String nameOf(List<AdObject> objs, String id) {
        if (objs != null) for (AdObject o : objs) if (o.id.equals(id)) return o.name;
        return id;
    }

    private static <T> List<T> uniq(List<T> a) { return new ArrayList<>(new LinkedHashSet<>(a)); }

    private static <T> List<T> inter(List<T> a, List<T> b) { return a.stream().filter(b::contains).toList(); }

    private static <T> boolean sameSet(List<T> a, List<T> b) { return a.size() == b.size() && b.containsAll(a); }

    private static String list(List<AdObject> objs, List<String> ids) {
        String s = String.join(", ", ids.stream().limit(2).map(id -> "“" + nameOf(objs, id) + "”").toList());
        return s + (ids.size() > 2 ? " và " + (ids.size() - 2) + " mục khác" : "");
    }

    /** Lọc theo điều kiện: { level, op, x, y, name, status, account } → (lỗi, giá trị đã chuẩn hoá) */
    static Map<String, Object> checkFilter(JsonNode f, Map<String, String> e) {
        if (f == null || !f.isObject()) f = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        String level = "adset".equals(Json.str(f.get("level"))) ? "adset" : "campaign";
        String op = FILTER_OPS.contains(Json.str(f.get("op"))) ? Json.str(f.get("op")) : "any";
        double x = Json.num(f.get("x")), y = Json.num(f.get("y"));
        if (!op.equals("any") && !(x >= 0)) e.put("x", "Nhập mức ngân sách để so sánh");
        if (op.equals("between") && !(y >= 0)) e.put("y", "Nhập mức thứ hai của khoảng");
        String name = Json.str(f.get("name")).trim();
        if (name.length() > 100) e.put("name", "Cụm tên tối đa 100 ký tự");
        String account = Json.str(f.get("account")).trim();
        if (account.length() > 40) account = account.substring(0, 40);
        String st = Json.str(f.get("status"));
        String status = FILTER_STATUSES.contains(st) ? st : Json.truthy(f.get("onlyRunning")) ? "running" : "all";
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("level", level);
        v.put("op", op);
        if (!op.equals("any")) v.put("x", x);
        if (op.equals("between")) v.put("y", y);
        v.put("name", name);
        v.put("status", status);
        v.put("onlyRunning", status.equals("running"));
        if (!account.isEmpty()) v.put("account", account);
        return v;
    }

    /** Cảnh báo cho danh sách mục đã chọn: bật xong vẫn không phân phối, chọn cả camp lẫn nhóm QC bên trong */
    static List<String> targetWarnings(List<String> targets, List<AdObject> objs, boolean turnsOn) {
        List<String> w = new ArrayList<>();
        Set<String> sel = new HashSet<>(targets);
        Map<String, AdObject> byId = new LinkedHashMap<>();
        for (AdObject o : objs) byId.put(o.id, o);
        java.util.function.Predicate<AdObject> live = o -> !"ARCHIVED".equals(o.effective) && !"DELETED".equals(o.effective);
        if (turnsOn) {
            List<String> emptyCamps = targets.stream().filter(id -> {
                AdObject o = byId.get(id);
                if (o == null || !o.isCampaign()) return false;
                List<AdObject> sets = objs.stream().filter(a -> "adset".equals(a.level) && id.equals(a.campaignId) && live.test(a)).toList();
                return !sets.isEmpty() && sets.stream().allMatch(a -> "PAUSED".equals(a.status) && !sel.contains(a.id));
            }).toList();
            if (!emptyCamps.isEmpty()) w.add("Mọi nhóm QC trong " + list(objs, emptyCamps) + " đang tắt, bật chiến dịch xong vẫn không chạy. Hãy chọn thêm nhóm QC cần bật.");
            List<String> offParent = targets.stream().filter(id -> {
                AdObject o = byId.get(id);
                AdObject c = o != null && "adset".equals(o.level) ? byId.get(o.campaignId) : null;
                return c != null && "PAUSED".equals(c.status) && !sel.contains(c.id);
            }).toList();
            if (!offParent.isEmpty()) w.add(list(objs, offParent) + " thuộc chiến dịch đang tắt, bật nhóm QC xong vẫn không chạy. Hãy chọn thêm chiến dịch đó.");
        }
        List<String> dup = targets.stream().filter(id -> { AdObject o = byId.get(id); return o != null && "adset".equals(o.level) && sel.contains(o.campaignId); }).toList();
        if (!dup.isEmpty() && !turnsOn)
            w.add("Đã chọn cả chiến dịch lẫn nhóm QC bên trong nó (" + list(objs, dup) + "). Tắt chiến dịch là đủ; nếu tắt cả nhóm QC thì lịch bật lại cũng phải chọn các nhóm QC đó, nếu không camp bật lên vẫn không chạy.");
        return w;
    }

    public static Result<Schedule> validate(JsonNode input, List<AdObject> objs, List<Schedule> schedules) {
        Result.Collector c = new Result.Collector();
        Map<String, String> e = c.e;
        String name = Json.str(input.get("name")).trim();
        if (name.length() > Checks.NAME_MAX) e.put("name", "Tên tối đa " + Checks.NAME_MAX + " ký tự");

        String action = input.path("action").isString() ? input.get("action").stringValue() : null;
        if (!ACTIONS.contains(action)) e.put("action", "Hành động không hợp lệ");

        // Khung giờ: bật lúc window.on, tắt lúc window.off (cùng danh sách camp)
        Map<String, Object> win = null;
        List<String> times;
        if ("window".equals(action)) {
            JsonNode iw = input.path("window");
            String on = Json.str(iw.get("on")), off = Json.str(iw.get("off"));
            win = new LinkedHashMap<>();
            win.put("on", on);
            win.put("off", off);
            if (!Checks.isTime(on) || !Checks.isTime(off)) e.put("time", "Chọn giờ bật và giờ tắt (dạng HH:MM, ví dụ 06:00)");
            else if (on.equals(off)) e.put("time", "Giờ tắt phải khác giờ bật");
            times = e.containsKey("time") ? List.of() : new ArrayList<>(new TreeSet<>(List.of(on, off)));
            if (!e.containsKey("time") && off.compareTo(on) < 0) c.warn("Giờ tắt " + off + " sớm hơn giờ bật nên camp tắt vào " + off + " sáng hôm sau.");
        } else {
            List<String> rawTimes = input.path("times").isArray() ? Json.strings(input.get("times"))
                    : Json.truthy(input.get("time")) ? List.of(Json.str(input.get("time"))) : List.of();
            times = new ArrayList<>(new TreeSet<>(rawTimes));
            if (rawTimes.isEmpty()) e.put("time", "Hãy thêm ít nhất 1 giờ chạy");
            else if (rawTimes.stream().anyMatch(t -> !Checks.isTime(t))) e.put("time", "Giờ chạy không hợp lệ (dạng HH:MM, ví dụ 06:00)");
            else if (times.size() > Checks.SCHEDULE_TIMES_MAX) e.put("time", "Tối đa " + Checks.SCHEDULE_TIMES_MAX + " giờ chạy mỗi ngày");
        }

        TreeSet<Integer> daySet = new TreeSet<>();
        if (input.path("days").isArray()) for (JsonNode d : input.get("days")) {
            double n = Json.num(d);
            if (n == Math.rint(n) && n >= 0 && n <= 6) daySet.add((int) n);
        }
        List<Integer> days = new ArrayList<>(daySet);
        if (days.isEmpty()) e.put("days", "Hãy chọn ít nhất 1 ngày trong tuần");

        // Áp dụng cho: danh sách cố định (list) hoặc theo điều kiện (filter, lọc lại mỗi lần chạy)
        String targetMode = "filter".equals(Json.str(input.get("targetMode"))) ? "filter" : "list";
        List<String> targets = new ArrayList<>(), exclude = new ArrayList<>();
        Map<String, Object> filter = null;
        if (targetMode.equals("filter")) {
            Map<String, String> fe = new LinkedHashMap<>();
            filter = checkFilter(input.get("filter"), fe);
            if (!fe.isEmpty()) e.put("filter", fe.values().iterator().next());
            exclude = uniq(Json.strings(input.get("exclude")));
            if (exclude.size() > 2000) exclude = exclude.subList(0, 2000);
            if (!e.containsKey("filter") && "any".equals(filter.get("op")) && "".equals(filter.get("name")) && "all".equals(filter.get("status")))
                c.warn("Điều kiện đang khớp " + ("adset".equals(filter.get("level")) ? "mọi nhóm QC" : "mọi chiến dịch") + " trên tài khoản.");
        } else {
            targets = uniq(Json.strings(input.get("targets")));
            if (targets.isEmpty()) e.put("targets", "Hãy chọn ít nhất 1 chiến dịch hoặc nhóm QC");
            else if (objs != null) {
                List<String> unknown = targets.stream().filter(id -> objs.stream().noneMatch(o -> o.id.equals(id))).toList();
                if (!unknown.isEmpty()) e.put("targets", "Có mục không còn tồn tại trên tài khoản: " + String.join(", ", unknown.stream().limit(3).toList()) + ". Hãy bỏ chọn chúng.");
            }
        }

        String im = Json.str(input.get("mode"));
        String mode = im.equals("set") || im.equals("add") ? im : "percent";
        double value = Json.num(input.get("value"));
        if ("budget".equals(action)) {
            if (!Double.isFinite(value)) e.put("value", "Nhập giá trị đổi ngân sách");
            else if (mode.equals("add")) {
                if (value == 0) e.put("value", "Số tiền cộng/trừ phải khác 0");
                else if (Math.abs(value) > Checks.BUDGET_MAX) e.put("value", "Số tiền quá lớn, hãy kiểm tra lại số 0");
                else value = Math.round(value);
                if (!e.containsKey("value") && !e.containsKey("time") && times.size() > 1)
                    c.warn("Ngân sách sẽ " + (value > 0 ? "cộng" : "trừ") + " " + Fmt.money(Math.abs(value)) + " " + times.size() + " lần mỗi ngày và cộng dồn.");
            } else if (mode.equals("percent")) {
                if (value == 0) e.put("value", "Phần trăm phải khác 0");
                else if (value <= -100) e.put("value", "Không thể giảm từ 100% trở lên (ngân sách sẽ về 0). Tối đa -90%.");
                else if (value < -Checks.RULE_PCT_DECREASE_MAX) e.put("value", "Giảm tối đa " + Checks.RULE_PCT_DECREASE_MAX + "% mỗi lần");
                else if (value > Checks.SCHEDULE_SET_PCT_MAX) e.put("value", "Tăng tối đa " + Checks.SCHEDULE_SET_PCT_MAX + "% mỗi lần");
                else if (Math.abs(value) >= 50) c.warn((value > 0 ? "Tăng" : "Giảm") + " " + Fmt.num(Math.abs(value)) + "% một lần là thay đổi lớn, Facebook có thể học lại từ đầu.");
                if (!e.containsKey("value") && !e.containsKey("time") && times.size() > 1)
                    c.warn("Ngân sách sẽ " + (value > 0 ? "tăng" : "giảm") + " " + Fmt.num(Math.abs(value)) + "% " + times.size()
                            + " lần mỗi ngày và cộng dồn (lần sau tính trên ngân sách đã đổi). Nên đặt trần/sàn ngân sách hoặc dùng số tiền cố định.");
            } else {
                if (value <= 0) e.put("value", "Ngân sách phải lớn hơn 0");
                else if (value > Checks.BUDGET_MAX) e.put("value", "Ngân sách quá lớn, hãy kiểm tra lại số 0");
                else value = Math.round(value);
            }
            if (targetMode.equals("list") && !e.containsKey("targets") && objs != null) {
                List<String> cbo = targets.stream().filter(id -> objs.stream().anyMatch(o -> o.id.equals(id) && o.dailyBudget == null)).toList();
                if (!cbo.isEmpty())
                    e.put("targets", "Các mục sau không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO): "
                            + String.join(", ", cbo.stream().limit(3).map(id -> nameOf(objs, id)).toList()) + (cbo.size() > 3 ? "…" : "")
                            + ". Hãy bỏ chúng hoặc chọn mục có ngân sách.");
            }
        }

        // Những lựa chọn khiến lịch chạy xong vẫn không như ý (chỉ cảnh báo)
        boolean turnsOn = "on".equals(action) || "window".equals(action);
        if (targetMode.equals("filter") && filter != null && !e.containsKey("filter")) {
            Object st = filter.get("status");
            if ("window".equals(action) && !"all".equals(st))
                e.put("filter", "Lịch khung giờ theo điều kiện cần trạng thái “Tất cả”: tool lọc lại lúc bật và lúc tắt, lọc “Đang chạy”/“Đang tắt” sẽ ra 2 danh sách khác nhau.");
            else if ("on".equals(action) && "running".equals(st))
                c.warn("Lịch bật nhưng điều kiện chỉ lấy mục đang chạy nên không có gì để bật. Chọn trạng thái “Đang tắt” hoặc “Tất cả”.");
            else if ("off".equals(action) && "off".equals(st))
                c.warn("Lịch tắt nhưng điều kiện chỉ lấy mục đang tắt nên không có gì để tắt. Chọn trạng thái “Đang chạy” hoặc “Tất cả”.");
        }
        if (targetMode.equals("list") && objs != null && !e.containsKey("targets")) targetWarnings(targets, objs, turnsOn).forEach(c::warn);

        // Xung đột với các lịch đang bật (so từng lần chạy); lịch theo điều kiện không có danh sách cố định nên không so được
        boolean enabled = !(input.path("enabled").isBoolean() && !input.get("enabled").booleanValue());
        String inputId = Json.truthy(input.get("id")) ? Json.str(input.get("id")) : null;
        if (enabled && targetMode.equals("list") && !e.containsKey("time") && !e.containsKey("days") && !e.containsKey("targets") && !e.containsKey("action")) {
            Schedule mineS = new Schedule();
            mineS.setAction(action);
            mineS.setTimes(times);
            mineS.setWindow(win);
            List<ScheduleRunner.Event> mine = ScheduleRunner.events(mineS);
            for (Schedule o : schedules) {
                if (o.getId() != null && o.getId().equals(inputId)) continue;
                if (!o.isEnabled()) continue;
                List<ScheduleRunner.Event> theirs = ScheduleRunner.events(o);
                List<Integer> sharedDays = inter(days, o.getDays());
                List<String> sharedTargets = inter(targets, o.getTargets() == null ? List.of() : o.getTargets());
                if (sharedDays.isEmpty() || sharedTargets.isEmpty()) continue;
                String names = String.join(", ", sharedTargets.stream().limit(2).map(id -> nameOf(objs, id)).toList());
                List<ScheduleRunner.Event[]> clash = new ArrayList<>();
                for (ScheduleRunner.Event a : mine) for (ScheduleRunner.Event b : theirs) if (b.time().equals(a.time())) clash.add(new ScheduleRunner.Event[]{a, b});
                ScheduleRunner.Event[] opp = clash.stream().filter(p -> ("on".equals(p[0].action()) && "off".equals(p[1].action()))
                        || ("off".equals(p[0].action()) && "on".equals(p[1].action()))).findFirst().orElse(null);
                if (opp != null) {
                    e.put("conflict", "Xung đột với lịch “" + o.getName() + "”: cùng lúc " + opp[0].time() + " nhưng làm điều ngược lại ("
                            + ("on".equals(opp[1].action()) ? "bật" : "tắt") + ") cho " + names + ".");
                    break;
                }
                boolean sameTargets = sameSet(days, o.getDays()) && sameSet(targets, o.getTargets() == null ? List.of() : o.getTargets());
                String oMode = "set".equals(o.getMode()) || "add".equals(o.getMode()) ? o.getMode() : "percent";
                boolean identical = Objects.equals(action, o.getAction()) && sameTargets && ("window".equals(action)
                        ? o.getWindow() != null && Objects.equals(o.windowOn(), win.get("on")) && Objects.equals(o.windowOff(), win.get("off"))
                        : (!"budget".equals(action) || (mode.equals(oMode) && o.getValue() == value)) && sameSet(times, ScheduleRunner.times(o)));
                if (identical) { e.put("conflict", "Đã có lịch giống hệt: “" + o.getName() + "”."); break; }
                String at = String.join(", ", new LinkedHashSet<>(clash.stream().map(p -> p[0].time()).toList()));
                if ("budget".equals(action) && "budget".equals(o.getAction()) && !clash.isEmpty())
                    c.warn("Lịch “" + o.getName() + "” cũng đổi ngân sách của " + names + " lúc " + at + ", kết quả có thể khó đoán.");
            }
        }

        Schedule s = new Schedule();
        s.setId(inputId);
        s.setName(name.isEmpty() ? "Lịch mới" : name);
        s.setAction(action);
        s.setTime(times.isEmpty() ? null : times.getFirst());
        s.setTimes(times);
        s.setDays(days);
        s.setWindow(win);
        s.setTargetMode(targetMode);
        s.setTargets(targets);
        s.setFilter(filter);
        s.setExclude(exclude.isEmpty() ? null : exclude);
        s.setMode(mode);
        s.setValue(Double.isFinite(value) ? value : 0);
        s.setEnabled(enabled);
        double max = Json.num(input.get("max")), min = Json.num(input.get("min"));
        s.setMax(max > 0 ? max : null);
        s.setMin(min > 0 ? min : null);
        return c.done(s);
    }
}
