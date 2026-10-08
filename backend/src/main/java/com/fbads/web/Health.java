package com.fbads.web;

/** GET /api/health: ok = vòng tự động còn chạy; lastTickAt = lúc xong lượt gần nhất (ISO) */
public record Health(boolean ok, String lastTickAt) {}
