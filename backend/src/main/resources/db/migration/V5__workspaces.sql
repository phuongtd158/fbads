-- Nhiều người dùng: mỗi người một tài khoản đăng nhập, dữ liệu chia theo workspace (không gian làm việc).
-- Workspace = 1 bộ cài đặt + token Facebook + lịch + rule + nhật ký. Thành viên của workspace có vai trò OWNER / EDITOR / VIEWER.
-- Dữ liệu đang có chuyển hết vào workspace số 1. Chạy được trên cả MariaDB và MySQL 8.

CREATE TABLE workspaces (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(3)  NOT NULL
);
INSERT INTO workspaces (id, name, created_at) VALUES (1, 'Workspace chính', NOW(3));

-- username: tên đăng nhập (có thể là email). password_hash dạng {bcrypt}… hoặc {scrypt-node}… (mật khẩu cũ của bản Node)
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(190) NOT NULL,
    name          VARCHAR(100) NOT NULL DEFAULT '',
    password_hash VARCHAR(300) NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    CONSTRAINT users_username UNIQUE (username)
);

CREATE TABLE workspace_members (
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(10) NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (workspace_id, user_id),
    INDEX workspace_members_user (user_id),
    CONSTRAINT workspace_members_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT workspace_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Đã đặt mật khẩu trong Cài đặt (1 mật khẩu chung) → thành tài khoản "admin", chủ workspace 1, đăng nhập bằng mật khẩu cũ.
INSERT INTO users (username, name, password_hash, created_at)
    SELECT 'admin', 'Admin', CONCAT('{scrypt-node}', password_hash), NOW(3) FROM app_settings WHERE id = 1 AND password_hash <> '';
INSERT INTO workspace_members (workspace_id, user_id, role, created_at)
    SELECT 1, id, 'OWNER', NOW(3) FROM users WHERE username = 'admin';

-- Cài đặt: mỗi workspace một dòng, id = id của workspace (dòng cũ id = 1 = workspace 1). Mật khẩu chuyển sang bảng users.
ALTER TABLE app_settings MODIFY id BIGINT NOT NULL;
ALTER TABLE app_settings DROP COLUMN password_hash;
ALTER TABLE app_settings ADD CONSTRAINT app_settings_ws FOREIGN KEY (id) REFERENCES workspaces (id) ON DELETE CASCADE;

-- Các bảng dữ liệu: thêm workspace_id (dữ liệu cũ = 1). Bỏ giá trị mặc định sau đó để dòng mới thiếu workspace bị từ chối.
ALTER TABLE schedules ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE schedules ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE schedules ADD INDEX schedules_ws (workspace_id, seq);
ALTER TABLE schedules ADD CONSTRAINT schedules_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

ALTER TABLE rules ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE rules ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE rules ADD INDEX rules_ws (workspace_id, seq);
ALTER TABLE rules ADD CONSTRAINT rules_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

-- Nhật ký giữ 1000 dòng mới nhất cho TỪNG workspace (LogService.trim)
ALTER TABLE logs ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE logs ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE logs ADD INDEX logs_ws (workspace_id, seq);
ALTER TABLE logs ADD CONSTRAINT logs_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

ALTER TABLE schedule_runs ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE schedule_runs ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE schedule_runs ADD CONSTRAINT schedule_runs_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

ALTER TABLE rule_marks ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE rule_marks ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE rule_marks ADD CONSTRAINT rule_marks_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

ALTER TABLE rule_resumes ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE rule_resumes ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE rule_resumes ADD CONSTRAINT rule_resumes_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

-- Đánh dấu theo ngày và thống kê: khoá chính có thêm workspace (vd. "report" = báo cáo hôm nay đã gửi, mỗi workspace một dấu)
ALTER TABLE daily_marks ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE daily_marks ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE daily_marks DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, day, mark);
ALTER TABLE daily_marks ADD CONSTRAINT daily_marks_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;

ALTER TABLE event_stats ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE event_stats ALTER COLUMN workspace_id DROP DEFAULT;
ALTER TABLE event_stats DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, day, source);
ALTER TABLE event_stats ADD CONSTRAINT event_stats_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;
