-- Chạy 24/7 ổn định, cảnh báo bất thường, báo cáo tuần (port từ bản Node, PR #12, #13, #15).

-- Trạng thái nhỏ của từng workspace, dạng khoá → JSON: đã báo token sắp hết hạn chưa, trạng thái tài khoản quảng cáo đã biết,
-- quảng cáo bị từ chối đã báo, báo cáo tuần đã gửi… (thay cho data.state của bản Node). Xoá workspace thì xoá theo.
CREATE TABLE workspace_state (
    workspace_id BIGINT       NOT NULL,
    k            VARCHAR(100) NOT NULL,
    v            MEDIUMTEXT   NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (workspace_id, k),
    CONSTRAINT workspace_state_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

-- Cài đặt mới
ALTER TABLE app_settings ADD COLUMN alert_account     BOOLEAN NOT NULL DEFAULT TRUE;   -- tài khoản quảng cáo bị vô hiệu hoá / nợ thanh toán…
ALTER TABLE app_settings ADD COLUMN alert_disapproved BOOLEAN NOT NULL DEFAULT TRUE;   -- quảng cáo bị từ chối
ALTER TABLE app_settings ADD COLUMN alert_spike       BOOLEAN NOT NULL DEFAULT TRUE;   -- chi tiêu tăng vọt so với cùng giờ hôm qua
ALTER TABLE app_settings ADD COLUMN spike_pct         INT     NOT NULL DEFAULT 50;
ALTER TABLE app_settings ADD COLUMN spike_min_spend   BIGINT  NOT NULL DEFAULT 100000;
ALTER TABLE app_settings ADD COLUMN weekly_report     BOOLEAN NOT NULL DEFAULT TRUE;   -- báo cáo tuần sáng thứ Hai
