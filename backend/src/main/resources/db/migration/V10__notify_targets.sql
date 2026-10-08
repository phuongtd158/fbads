-- Kênh thông báo đã cài của mỗi workspace (Telegram, Gmail…). Một workspace có thể có nhiều kênh, kể cả 2 kênh cùng loại
-- (vd 2 nhóm Telegram nhận 2 loại tin khác nhau). Cấu hình Telegram cũ trong app_settings được chuyển sang đây lúc khởi
-- động (notify/LegacyTelegramMove), vì token đang được mã hoá bằng SECRET_KEY nên không chuyển bằng SQL được.
CREATE TABLE notify_targets (
    id           VARCHAR(16)  NOT NULL PRIMARY KEY,
    seq          BIGINT       NOT NULL AUTO_INCREMENT UNIQUE,
    workspace_id BIGINT       NOT NULL,
    type         VARCHAR(20)  NOT NULL,             -- loại kênh: telegram | email
    name         VARCHAR(100) NOT NULL DEFAULT '',  -- tên người dùng đặt, vd "Nhóm Telegram team A"
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    topics       JSON         NOT NULL,             -- loại tin nhận: ["LOG","ALERT","REPORT","COMPANY"]
    config       TEXT         NOT NULL,             -- JSON cấu hình của kênh (token, người nhận…), mã hoá cả chuỗi bằng SECRET_KEY
    CONSTRAINT notify_targets_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);
