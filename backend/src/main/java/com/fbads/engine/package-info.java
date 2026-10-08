/**
 * Vòng tự động: EngineTicker được Spring gọi mỗi 30 giây, chạy lịch (schedule/ScheduleRunner), rule (rule/RuleRunner →
 * rule/RuleEvaluator → ActionExecutor), cảnh báo, dừng khẩn.
 * <p>
 * Đọc trước: EngineTicker, rồi ScheduleRunner hoặc RuleRunner. RuleEvaluator chỉ tính toán; mọi thay đổi thật nằm
 * ở ActionExecutor.
 */
package com.fbads.engine;
