-- Giai đoạn 2: phiên đăng nhập chuyển sang Redis (Spring Session Data Redis) → bỏ 2 bảng phiên cũ.
DROP TABLE IF EXISTS SPRING_SESSION_ATTRIBUTES;
DROP TABLE IF EXISTS SPRING_SESSION;
