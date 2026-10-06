-- Rule (port từ bản Node, PR #13, #24, #25):
--  - điều kiện so với chính số liệu đó ở khoảng thời gian khác (vs = range) và ngưỡng chi tiêu nâng theo số kết quả (tiers):
--    nằm trong cột conditions (JSON) nên không cần đổi bảng;
--  - rule "tăng theo bậc kết quả" (action = ladder): loại kết quả tính bậc, các bậc, có tăng cả nhóm đang học không.
ALTER TABLE rules ADD COLUMN ladder_metric    VARCHAR(20) NULL;
ALTER TABLE rules ADD COLUMN steps            JSON        NULL;
ALTER TABLE rules ADD COLUMN include_learning BOOLEAN     NULL;

-- Bậc đã chạy hôm nay của từng cặp rule + camp (rule tăng theo bậc): ngày, bậc (0 = bậc 1), lúc chạy. Sang ngày mới tính lại.
ALTER TABLE rule_marks ADD COLUMN ladder_date VARCHAR(10) NULL;
ALTER TABLE rule_marks ADD COLUMN ladder_step INT         NULL;
ALTER TABLE rule_marks ADD COLUMN ladder_at_ms BIGINT     NULL;
