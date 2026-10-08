package com.fbads.log;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API nhật ký: 300 dòng gần nhất và hoàn tác một dòng. Việc thật nằm ở LogService, UndoService. */
@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final LogService logs;
    private final UndoService undo;

    public LogController(LogService logs, UndoService undo) {
        this.logs = logs;
        this.undo = undo;
    }

    @GetMapping
    List<LogEntry> recent() { return logs.recent(300); }

    @PostMapping("/{id}/undo")
    Undone undo(@PathVariable String id, @RequestBody(required = false) Undo b) {
        return new Undone(true, undo.undo(id, (b == null ? Undo.EMPTY : b).force()));
    }
}
