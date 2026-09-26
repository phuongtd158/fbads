-- Lược đồ dữ liệu của tool (thay cho data.json của bản Node). Flyway chạy file này 1 lần khi khởi động lần đầu.
-- Cột JSON của MariaDB là LONGTEXT có kiểm tra json_valid; Java đọc/ghi qua AttributeConverter (xem common/JsonConverters).

-- Cài đặt: chỉ có 1 dòng (id = 1)
CREATE TABLE app_settings (
    id                   TINYINT      NOT NULL PRIMARY KEY,
    version              INT          NOT NULL DEFAULT 0,
    mock                 BOOLEAN      NOT NULL DEFAULT TRUE,
    dry_run              BOOLEAN      NOT NULL DEFAULT TRUE,
    access_token         TEXT         NOT NULL DEFAULT '',
    ad_account_id        VARCHAR(40)  NOT NULL DEFAULT '',
    ad_account_ids       JSON         NOT NULL DEFAULT '[]',
    fb_app_id            VARCHAR(40)  NOT NULL DEFAULT '',
    fb_app_secret        VARCHAR(100) NOT NULL DEFAULT '',
    fb_config_id         VARCHAR(40)  NOT NULL DEFAULT '',
    api_version          VARCHAR(10)  NOT NULL DEFAULT 'v21.0',
    timezone             VARCHAR(64)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    result_action        VARCHAR(100) NOT NULL DEFAULT 'purchase',
    rule_interval_min    INT          NOT NULL DEFAULT 15,
    telegram_token       VARCHAR(200) NOT NULL DEFAULT '',
    telegram_chat_id     VARCHAR(500) NOT NULL DEFAULT '',
    report_time          VARCHAR(5)   NOT NULL DEFAULT '08:00',
    password_hash        VARCHAR(300) NOT NULL DEFAULT '',
    skip_learning        BOOLEAN      NOT NULL DEFAULT TRUE,
    daily_change_cap_pct INT          NOT NULL DEFAULT 30,
    kill_switch_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    daily_spend_limit    BIGINT       NOT NULL DEFAULT 0,
    kill_scope           VARCHAR(10)  NOT NULL DEFAULT 'total',
    account_targets      JSON         NOT NULL DEFAULT '{}'
);
INSERT INTO app_settings (id) VALUES (1);

-- Lịch bật/tắt/đổi ngân sách. seq giữ thứ tự tạo (danh sách hiện theo thứ tự này).
CREATE TABLE schedules (
    id           VARCHAR(16)  NOT NULL PRIMARY KEY,
    seq          BIGINT       NOT NULL AUTO_INCREMENT UNIQUE,
    name         VARCHAR(100) NOT NULL,
    action       VARCHAR(10)  NOT NULL,
    first_time   VARCHAR(5),
    times        JSON         NOT NULL,
    days         JSON         NOT NULL,
    window_json  JSON,
    target_mode  VARCHAR(10)  NOT NULL DEFAULT 'list',
    targets      JSON         NOT NULL,
    filter_json  JSON,
    exclude_json JSON,
    budget_mode  VARCHAR(10)  NOT NULL DEFAULT 'percent',
    budget_value DOUBLE       NOT NULL DEFAULT 0,
    max_budget   DOUBLE,
    min_budget   DOUBLE,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Rule theo hiệu quả (CPA, ROAS, chi tiêu…)
CREATE TABLE rules (
    id             VARCHAR(16)  NOT NULL PRIMARY KEY,
    seq            BIGINT       NOT NULL AUTO_INCREMENT UNIQUE,
    name           VARCHAR(100) NOT NULL,
    metric         VARCHAR(20),
    op             VARCHAR(1),
    threshold      DOUBLE       NOT NULL DEFAULT 0,
    conditions     JSON         NOT NULL,
    match_mode     VARCHAR(3)   NOT NULL DEFAULT 'all',
    range_key      VARCHAR(10)  NOT NULL DEFAULT 'today',
    min_spend      DOUBLE       NOT NULL DEFAULT 0,
    action         VARCHAR(10)  NOT NULL,
    pct            DOUBLE       NOT NULL DEFAULT 0,
    budget_mode    VARCHAR(10)  NOT NULL DEFAULT 'percent',
    amount         DOUBLE       NOT NULL DEFAULT 0,
    max_budget     DOUBLE       NOT NULL DEFAULT 0,
    min_budget     DOUBLE       NOT NULL DEFAULT 0,
    cooldown_hours DOUBLE       NOT NULL DEFAULT 0,
    resume_mode    VARCHAR(10)  NOT NULL DEFAULT '',
    resume_at      VARCHAR(5)   NOT NULL DEFAULT '',
    from_time      VARCHAR(5)   NOT NULL DEFAULT '',
    to_time        VARCHAR(5)   NOT NULL DEFAULT '',
    all_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    ad_level       VARCHAR(10)  NOT NULL DEFAULT 'campaign',
    account_ids    JSON         NOT NULL,
    targets        JSON         NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Nhật ký: mọi thay đổi (thủ công, lịch, rule, hoàn tác, hệ thống). Giữ 1000 dòng mới nhất.
CREATE TABLE logs (
    id             VARCHAR(16) NOT NULL PRIMARY KEY,
    seq            BIGINT      NOT NULL AUTO_INCREMENT UNIQUE,
    ts             DATETIME(3) NOT NULL,
    kind           VARCHAR(20) NOT NULL,
    source         TEXT,
    name           TEXT,
    detail         TEXT,
    ok             BOOLEAN,
    mode           VARCHAR(10),
    dry            BOOLEAN,
    skipped        BOOLEAN,
    ref_id         VARCHAR(40),
    ref_name       VARCHAR(200),
    ref_log_id     VARCHAR(16),
    target         JSON,
    action_json    JSON,
    before_json    JSON,
    after_json     JSON,
    condition_json JSON,
    error_json     JSON,
    undone         JSON,
    INDEX logs_ref (kind, ref_id)
);

-- Mỗi mốc lịch đã chạy trong ngày. Khoá chính = "id lịch:ngày:giờ" → không mốc nào chạy 2 lần,
-- kể cả khi server khởi động lại hay có 2 bản app cùng chạy (INSERT thứ hai bị từ chối).
CREATE TABLE schedule_runs (
    run_key    VARCHAR(120) NOT NULL PRIMARY KEY,
    run_date   VARCHAR(10)  NOT NULL,
    created_at DATETIME(3)  NOT NULL,
    INDEX schedule_runs_date (run_date)
);

-- Theo từng cặp rule + camp: lần tác động gần nhất (thời gian nghỉ) và hạn tạm hoãn sau khi hoàn tác
CREATE TABLE rule_marks (
    rule_id       VARCHAR(16) NOT NULL,
    obj_id        VARCHAR(40) NOT NULL,
    last_run_ms   BIGINT,
    hold_until_ms BIGINT,
    PRIMARY KEY (rule_id, obj_id)
);

-- Camp do rule tắt, hẹn bật lại vào giờ resumeAt của ngày hôm sau
CREATE TABLE rule_resumes (
    rule_id  VARCHAR(16) NOT NULL,
    obj_id   VARCHAR(40) NOT NULL,
    off_date VARCHAR(10) NOT NULL,
    PRIMARY KEY (rule_id, obj_id)
);

-- Đánh dấu theo ngày: ngân sách gốc của camp (giới hạn thay đổi/ngày), lý do bỏ qua đã ghi, dừng khẩn đã chạy, báo cáo đã gửi
CREATE TABLE daily_marks (
    day       VARCHAR(10)  NOT NULL,
    mark      VARCHAR(200) NOT NULL,
    num_value DOUBLE,
    PRIMARY KEY (day, mark)
);
