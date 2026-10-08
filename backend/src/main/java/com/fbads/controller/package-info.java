/**
 * Nhận request HTTP /api/... từ giao diện, gọi service rồi trả JSON. Không chứa logic nghiệp vụ. Lỗi do
 * ApiExceptionHandler đổi thành {error}.
 * <p>
 * Controller của từng tính năng nằm cùng package tính năng đó (vd. ads/ObjectsController).
 */
package com.fbads.controller;
