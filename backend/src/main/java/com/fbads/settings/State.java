package com.fbads.settings;

import com.fbads.rule.Rule;
import com.fbads.schedule.Schedule;

import java.util.List;

/** GET /api/state: mọi thứ giao diện cần lúc mở */
public record State(PublicSettings settings, List<Schedule> schedules, List<Rule> rules, Storage storage) {}
