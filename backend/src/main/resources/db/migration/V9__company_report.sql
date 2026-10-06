-- Báo cáo lên hệ thống nội bộ của công ty theo mốc 9h / 12h / 17h / 22h (port từ bản Node, PR #27, #28, #30, #33).

-- Cài đặt của mỗi workspace (id = id của workspace). Mật khẩu web công ty mã hoá bằng SECRET_KEY như token Facebook.
CREATE TABLE company_config (
    id        BIGINT       NOT NULL PRIMARY KEY,
    enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    mode      VARCHAR(10)  NOT NULL DEFAULT 'approve',
    slots     JSON         NOT NULL,
    lead_min  INT          NOT NULL DEFAULT 0,
    base_url  VARCHAR(255) NOT NULL,
    email     VARCHAR(255) NOT NULL DEFAULT '',
    password  TEXT         NOT NULL,
    teams     JSON         NOT NULL,
    CONSTRAINT company_config_ws FOREIGN KEY (id) REFERENCES workspaces (id) ON DELETE CASCADE
);

-- Bản báo cáo: 1 Team công ty × 1 ngày × 1 mốc (mỗi workspace giữ 300 bản mới nhất)
CREATE TABLE company_reports (
    id                VARCHAR(16)  NOT NULL PRIMARY KEY,
    seq               BIGINT       NOT NULL AUTO_INCREMENT UNIQUE,
    workspace_id      BIGINT       NOT NULL,
    date_rule         INT          NOT NULL DEFAULT 3,
    team_id           VARCHAR(64)  NOT NULL,
    team_code         VARCHAR(40)  NOT NULL DEFAULT '',
    team_name         VARCHAR(120) NOT NULL DEFAULT '',
    report_date       VARCHAR(10)  NOT NULL,
    slot              INT          NOT NULL,
    metrics           JSON         NOT NULL,
    edited            JSON         NOT NULL,
    campaigns         JSON         NULL,
    notes             TEXT         NULL,
    issue             TEXT         NULL,
    resolution        TEXT         NULL,
    status            VARCHAR(10)  NOT NULL,
    error             TEXT         NULL,
    reasons           JSON         NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    next_try_at       DATETIME(3)  NULL,
    sent_as           VARCHAR(10)  NULL,
    sent_at           DATETIME(3)  NULL,
    remote            JSON         NULL,
    remote_id         VARCHAR(64)  NULL,
    remote_status     VARCHAR(30)  NULL,
    remote_updated_at DATETIME(3)  NULL,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NULL,
    built_at          DATETIME(3)  NULL,
    UNIQUE KEY company_reports_slot (workspace_id, team_id, report_date, slot),
    INDEX company_reports_ws (workspace_id, seq),
    CONSTRAINT company_reports_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);
