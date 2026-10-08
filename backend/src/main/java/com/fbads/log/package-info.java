/**
 * Nhật ký: mỗi lần bật/tắt, đổi ngân sách (tay, lịch, rule) là một dòng LogEntry trong bảng logs, và hoàn tác.
 * <p>
 * Đọc theo thứ tự: LogEntry (các phần lưu dạng JSON: LogTarget, LogAction, LogSnapshot, LogChange, LogCondition,
 * LogError, LogUndone) → LogService (ghi, đọc, phát sự kiện LOG_CREATED) → UndoService (hoàn tác) → LogController.
 */
package com.fbads.log;
