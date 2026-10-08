package com.fbads.facebook;

/** POST /api/fb/extend: token mới đã lưu và tình trạng của nó */
public record TokenExtended(boolean ok, TokenStatus token) {}
