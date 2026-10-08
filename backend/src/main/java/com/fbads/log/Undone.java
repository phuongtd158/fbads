package com.fbads.log;

/** POST /api/logs/{id}/undo */
public record Undone(boolean ok, LogEntry entry) {}
