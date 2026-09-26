# fbads — bản Java (Spring Boot)

Bản chuyển backend Node (`server.js`, `lib/`) sang **Java 21 + Spring Boot 4**, dữ liệu lưu ở **MariaDB**.
Giao diện Vue giữ nguyên: các API cùng đường dẫn, cùng dạng JSON, nên `web/` không phải sửa gì thêm
(chỉ thêm dòng "Nơi lưu dữ liệu: MariaDB" ở Cài đặt → Chung).

Mục đích chính là **học**. Bản Node trên nhánh `dev` vẫn là bản đang chạy thật.

## Chạy thử nhanh (Docker)

```bash
cd backend-java
APP_PASSWORD='MatKhau@2026' docker compose up --build
```

Mở http://localhost:3000 và đăng nhập bằng mật khẩu vừa đặt. Tool bắt đầu ở chế độ dữ liệu giả (mock).

- MariaDB chạy trong container `db`, còn dữ liệu nằm trong volume `db-data`. Xoá hết bằng `docker compose down -v`.
- Để xem bảng bằng DBeaver/HeidiSQL: kết nối `127.0.0.1:3306`, user `fbads`, mật khẩu `fbads`.

## Chạy khi đang code (không build image)

```bash
docker run -d --name fbads-db -p 127.0.0.1:3306:3306 \
  -e MARIADB_DATABASE=fbads -e MARIADB_USER=fbads -e MARIADB_PASSWORD=fbads -e MARIADB_ROOT_PASSWORD=root mariadb:11.8

cd backend-java
mvn spring-boot:run          # http://127.0.0.1:3000, dùng giao diện đã build sẵn ở ../public
```

Lần chạy đầu, Flyway tự tạo bảng (`src/main/resources/db/migration`).

## Mang dữ liệu cũ của bản Node sang

Tool chỉ nhập khi DB còn trống, nên để biến này lại cũng không bị nhập trùng.

| Nguồn | Biến môi trường |
|---|---|
| File `data.json` | `IMPORT_FILE=/đường/dẫn/data.json` |
| Upstash (Render) | `IMPORT_UPSTASH=true`, `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`, `DATA_KEY` (cùng khoá bản Node đang dùng) |

Những gì được nhập:
- cài đặt (kể cả mật khẩu đăng nhập cũ);
- lịch, rule, nhật ký;
- các camp đang chờ bật lại hôm sau.

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL` | `jdbc:mariadb://localhost:3306/fbads` | Địa chỉ MariaDB (MySQL 8 cũng chạy được) |
| `DB_USER`, `DB_PASSWORD` | `fbads` | Tài khoản DB |
| `PORT`, `HOST` | `3000`, `127.0.0.1` | Cổng và địa chỉ nghe |
| `APP_PASSWORD` | | Mật khẩu đăng nhập; bắt buộc khi `HOST` không phải máy này |
| `PUBLIC_URL` | | Địa chỉ công khai, dùng cho đăng nhập Facebook |
| `PUBLIC_DIR` | `../public` | Thư mục giao diện đã build |
| `ENGINE_ENABLED` | `true` | Tắt vòng chạy lịch/rule (dùng khi test) |

## Test

```bash
mvn test        # cần Docker: Testcontainers tự bật MariaDB thật cho mỗi test
```

- **NodeCompatTest** kiểm tra hai thứ do bản Node tạo ra (trong `src/test/resources/fixtures/`):
  - mật khẩu băm scrypt vẫn đăng nhập được;
  - dữ liệu mã hoá trên Upstash giải mã được.
- **ApiIntegrationTest** gọi API y như giao diện:
  - 20 ca kiểm tra lịch/rule cho kết quả giống hệt `shared/validate.mjs`;
  - lịch, thao tác tay, hoàn tác;
  - đăng nhập và các lớp chặn.
- **ImportIntegrationTest** khởi động với `data.json` mẫu, rồi kiểm tra dữ liệu đã vào DB.

## Đối chiếu Node → Spring (để học)

| Bản Node | Bản Java | Học được gì |
|---|---|---|
| `data.json` / Upstash (`lib/store.js`) | MariaDB + Spring Data JPA (`*Repository`), Flyway | Entity, repository, migration, `@Version` |
| `express.Router` (`lib/routes/*`) | `@RestController` (`web/`) | Mapping, `@RequestBody`, `ResponseEntity` |
| Middleware tự viết (`lib/middleware.js`, `lib/auth.js`) | Spring Security + Spring Session JDBC, filter `ApiFilters` | SecurityFilterChain, phiên lưu trong DB |
| `shared/validate.mjs` (chạy cả ở giao diện) | `validation/*Validator` (bản Java của cùng luật) + Bean Validation (`@Valid`, `@StrongPassword`) | Ràng buộc tự viết |
| `setInterval` trong `lib/engine.js` | `@Scheduled` (`EngineTicker`) + `EngineLock` | Lập lịch, khoá, virtual threads |
| `fetch` + tự thử lại (`lib/fb.js`) | `RestClient` + Resilience4j `@Retry` (`GraphClient`) | Client HTTP, retry có backoff |
| `/api/health` | Actuator `/actuator/health` | Theo dõi sức khoẻ ứng dụng |

## Bản Java chắc chắn hơn ở mấy chỗ

- **Chạy ngay** và vòng tự động dùng chung một khoá (`EngineLock`), nên không bao giờ chạy chồng lên nhau trên cùng một camp.
- Mỗi bước của vòng tự động có `try/catch` riêng: lịch lỗi thì rule và báo cáo vẫn chạy.
- Mỗi lần chạy lịch được "giữ chỗ" bằng khoá chính trong DB (`schedule_runs`), nên không chạy 2 lần kể cả khi khởi động lại giữa chừng.
- Dữ liệu ghi thẳng vào DB theo từng thay đổi, không còn cảnh ghi cả file hay đồng bộ chậm lên Upstash.

## Đưa lên mạng

- **Render không có MariaDB.** Có 3 cách:
  - app trên Render, DB ở dịch vụ ngoài (ví dụ Aiven MySQL gói free; đặt `DB_URL=jdbc:mariadb://...`);
  - chạy cả app và DB trên VPS bằng `docker compose` (xem `deploy/chay-24-7-vps.md` ở nhánh `dev`);
  - dùng MySQL có sẵn ở chỗ khác.
- Image đã giới hạn bộ nhớ JVM (`MaxRAMPercentage=70`, SerialGC), nên chạy vừa gói 512 MB. Đo thử được khoảng 340 MB.
- Build image từ thư mục gốc repo: `docker build -f backend-java/Dockerfile .`

## Giai đoạn tiếp theo (đã bàn)

1. **Redis**:
   - cache danh sách camp;
   - Spring Session trên Redis;
   - ShedLock để chạy nhiều bản cùng lúc.
2. **WebSocket (STOMP)**: đẩy nhật ký và thay đổi camp lên giao diện ngay, không phải tải lại.
3. **Kafka**:
   - phát sự kiện "đã tắt camp" và "đã đổi ngân sách";
   - chỉ chạy ở máy qua Compose, bật/tắt được;
   - khi tắt thì dùng Spring events thay thế.
