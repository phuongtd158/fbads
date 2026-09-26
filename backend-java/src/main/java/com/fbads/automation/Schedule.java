package com.fbads.automation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fbads.common.JsonConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lịch tự động: bật / tắt / đổi ngân sách / khung giờ (bật lúc on, tắt lúc off) vào các giờ cố định trong tuần.
 * Áp dụng cho danh sách cố định (targets) hoặc theo điều kiện (filter, lọc lại mỗi lần chạy).
 * Tên thuộc tính JSON giữ như bản Node để giao diện dùng nguyên.
 */
@Entity
@Table(name = "schedules")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "action", "time", "times", "days", "window", "targetMode", "targets", "filter", "exclude", "mode", "value", "enabled", "max", "min"})
public class Schedule {
    @Id
    private String id;
    @Column(insertable = false, updatable = false)
    @JsonIgnore
    private Long seq;
    private String name;
    private String action;              // on | off | budget | window
    @Column(name = "first_time")
    private String time;                // giờ chạy sớm nhất (giữ cho dữ liệu cũ)
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> times = new ArrayList<>();
    @Convert(converter = JsonConverters.IntList.class)
    private List<Integer> days = new ArrayList<>();   // 0 = Chủ nhật … 6 = Thứ bảy
    @Column(name = "window_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> window; // { on: "HH:MM", off: "HH:MM" } khi action = window
    private String targetMode = "list"; // list | filter
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> targets = new ArrayList<>();
    @Column(name = "filter_json")
    @Convert(converter = JsonConverters.AnyMap.class)
    private Map<String, Object> filter;
    @Column(name = "exclude_json")
    @Convert(converter = JsonConverters.StringList.class)
    private List<String> exclude;
    @Column(name = "budget_mode")
    private String mode = "percent";    // set | add | percent
    @Column(name = "budget_value")
    private double value;
    private boolean enabled = true;
    @Column(name = "max_budget")
    private Double max;
    @Column(name = "min_budget")
    private Double min;

    public String windowOn() { return window == null ? null : (String) window.get("on"); }
    public String windowOff() { return window == null ? null : (String) window.get("off"); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public List<String> getTimes() { return times; }
    public void setTimes(List<String> times) { this.times = times; }
    public List<Integer> getDays() { return days; }
    public void setDays(List<Integer> days) { this.days = days; }
    public Map<String, Object> getWindow() { return window; }
    public void setWindow(Map<String, Object> window) { this.window = window; }
    public String getTargetMode() { return targetMode; }
    public void setTargetMode(String targetMode) { this.targetMode = targetMode; }
    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets; }
    public Map<String, Object> getFilter() { return filter; }
    public void setFilter(Map<String, Object> filter) { this.filter = filter; }
    public List<String> getExclude() { return exclude; }
    public void setExclude(List<String> exclude) { this.exclude = exclude; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    @JsonProperty("max")
    public Double getMax() { return max; }
    public void setMax(Double max) { this.max = max; }
    @JsonProperty("min")
    public Double getMin() { return min; }
    public void setMin(Double min) { this.min = min; }
}
