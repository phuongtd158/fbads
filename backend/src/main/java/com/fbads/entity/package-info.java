/**
 * Bảng trong MySQL (JPA). Trường @TenantId = workspace_id: Hibernate tự lọc và tự ghi theo workspace hiện tại. Đổi
 * bảng thì thêm file Flyway mới ở resources/db/migration.
 * <p>
 * Đọc trước: Schedule, Rule, LogEntry.
 */
package com.fbads.entity;
