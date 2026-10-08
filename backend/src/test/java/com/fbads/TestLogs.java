package com.fbads;

import com.fbads.log.LogEntry;
import com.fbads.log.LogKind;

/** Dòng nhật ký mẫu cho kiểm thử */
final class TestLogs {
    private TestLogs() {}

    static LogEntry entry(LogKind kind, String source, String name, String detail) {
        LogEntry e = LogEntry.of(kind, source, name);
        e.setDetail(detail);
        return e;
    }
}
