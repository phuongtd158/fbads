/**
 * Rule: tự tắt, bật hoặc đổi ngân sách camp khi số liệu khớp điều kiện.
 * <p>
 * Đọc theo thứ tự: RuleController (API) → RuleService → RuleValidator → Rule (bảng rules, cùng Condition,
 * RuleAction, RuleRange) → RuleRunner (engine gọi theo chu kỳ) → RuleEvaluator (chỉ tính toán, ra danh sách
 * quyết định; việc đổi thật nằm ở engine/ActionExecutor). RuleMark, RuleResume là dấu vết để không làm lặp và để
 * bật lại theo hẹn. RulePreview, RuleActivity là JSON trả về cho giao diện.
 */
package com.fbads.rule;
