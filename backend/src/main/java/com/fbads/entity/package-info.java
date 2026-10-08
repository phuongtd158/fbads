/**
 * Bảng trong MySQL (JPA). Trường @TenantId = workspace_id: Hibernate tự lọc và tự ghi theo workspace hiện tại. Đổi
 * bảng thì thêm file Flyway mới ở resources/db/migration.
 * <p>
 * Các bảng còn lại; lịch, rule, nhật ký, tài khoản, cài đặt đã về package tính năng (schedule/, rule/, log/, account/, settings/).
 */
package com.fbads.entity;
