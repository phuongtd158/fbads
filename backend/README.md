# Backend (Spring Boot)

Bản chuyển backend Node (`server.js`, `lib/`) sang **Java 21 + Spring Boot 4**, dữ liệu lưu ở **MariaDB**, cache/phiên đăng nhập/khoá/sự kiện realtime ở **Redis**.
Giao diện Vue (`../frontend`) gọi đúng các API cũ: cùng đường dẫn, cùng dạng JSON.

Mục đích chính là **học**. Bản Node (nhánh `dev`) vẫn là bản đang chạy thật; nhánh này đã bỏ hẳn code Node.

Cách chạy (Docker hoặc khi đang code) xem [README ở thư mục gốc](../README.md).

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
| `DB_URL` | `jdbc:mariadb://localhost:3306/fbads` | Địa chỉ MariaDB 10.6+ hoặc MySQL 8.0.13+. Với MySQL 8 thêm `?allowPublicKeyRetrieval=true` (xem dưới) |
| `DB_USER`, `DB_PASSWORD` | `fbads` | Tài khoản DB |
| `REDIS_URL` | `redis://localhost:6379` | Địa chỉ Redis (bắt buộc). Upstash: `rediss://default:MẬT_KHẨU@xxx.upstash.io:6379` |
| `REDIS_CONFIGURE_ACTION` | `notify-keyspace-events` | Đặt `none` khi Redis dịch vụ không cho lệnh `CONFIG` (Upstash, ElastiCache) |
| `PORT`, `HOST` | `3000`, `127.0.0.1` | Cổng và địa chỉ nghe |
| `APP_PASSWORD` | | Mật khẩu đăng nhập; bắt buộc khi `HOST` không phải máy này |
| `PUBLIC_URL` | | Địa chỉ công khai, dùng cho đăng nhập Facebook |
| `PUBLIC_DIR` | `../frontend/dist` | Thư mục giao diện đã build |
| `ENGINE_ENABLED` | `true` | Tắt vòng chạy lịch/rule (dùng khi test) |

### Dùng MySQL 8 thay MariaDB

- Giữ nguyên driver, chỉ đổi địa chỉ: `DB_URL=jdbc:mariadb://localhost:3306/fbads?allowPublicKeyRetrieval=true`.
  Thiếu tham số này sẽ gặp lỗi `RSA public key is not available client side` (MySQL 8 mặc định đăng nhập bằng `caching_sha2_password`).
- Lần chạy trước bị lỗi migration thì xoá DB tạo lại cho sạch: `DROP DATABASE fbads; CREATE DATABASE fbads;`.
- DB MariaDB tạo trước ngày 27/09/2026 có thể báo `Validate failed: Migration checksum mismatch for migration version 1`
  (file V1 đã sửa cho chạy được trên MySQL 8, lược đồ không đổi). Cách nhanh nhất cũng là xoá DB tạo lại rồi nhập lại dữ liệu bằng `IMPORT_FILE`.

## Test

```bash
mvn test        # cần Docker: Testcontainers tự bật MariaDB và Redis thật
```

- **NodeCompatTest** kiểm tra hai thứ do bản Node tạo ra (trong `src/test/resources/fixtures/`):
  - mật khẩu băm scrypt vẫn đăng nhập được;
  - dữ liệu mã hoá trên Upstash giải mã được.
- **ApiIntegrationTest** gọi API y như giao diện:
  - 20 ca kiểm tra lịch/rule cho kết quả giống hệt `frontend/src/shared/validate.mjs`;
  - lịch, thao tác tay, hoàn tác;
  - đăng nhập và các lớp chặn;
  - số liệu Facebook được cache ở Redis và bị xoá khi đổi ngân sách;
  - vòng tự động và nút "Chạy ngay" dùng chung khoá ShedLock;
  - phiên đăng nhập ở Redis, nhập sai 5 lần thì bị khoá 15 phút;
  - client STOMP nhận nhật ký và sự kiện camp ngay khi đổi ngân sách; chưa đăng nhập thì bị từ chối.
- **ImportIntegrationTest** khởi động với `data.json` mẫu, rồi kiểm tra dữ liệu đã vào DB.
- **MySqlCompatTest** chạy toàn bộ migration và ghi/đọc cài đặt, lịch, nhật ký trên MySQL 8.0 thật.

## Cấu trúc thư mục (chia theo tầng)

Luồng một request: `controller` → `service` → `repository` → DB.

| Thư mục | Chứa gì |
|---|---|
| `controller/` | Nhận/trả HTTP, không có logic; `ApiExceptionHandler` đổi lỗi thành JSON |
| `service/` | Nghiệp vụ: `ScheduleService`, `RuleService`, `ObjectService`, `SettingsService`, `LogService`, `AuthService`, `FacebookService`, `TelegramService`, `UndoService`, `ReportService`, `DataImporter` |
| `repository/` | Spring Data JPA, mỗi bảng 1 interface |
| `entity/` | Class ánh xạ bảng (`@Entity`) |
| `dto/` | Dữ liệu vào/ra không phải bảng: request, `AdObject`, `Metrics`, `Condition`, `Saved` |
| `client/` | Gọi dịch vụ ngoài: `GraphClient` (Facebook), giới hạn gọi API, dữ liệu giả |
| `engine/` | Logic chạy lịch/rule mỗi 30 giây (`EngineTicker`, `ScheduleRunner`, `RuleRunner`…) |
| `validation/` | Luật kiểm tra lịch/rule/cài đặt (giống `frontend/src/shared/validate.mjs`) |
| `security/` | Spring Security, mã hoá mật khẩu, filter chặn request lạ |
| `config/`, `common/` | Cấu hình Spring (Redis, cache, WebSocket, Jackson…) và tiện ích dùng chung |

## Đối chiếu Node → Spring (để học)

| Bản Node | Bản Java | Học được gì |
|---|---|---|
| `data.json` / Upstash (`lib/store.js`) | MariaDB + Spring Data JPA (`*Repository`), Flyway | Entity, repository, migration, `@Version` |
| `express.Router` (`lib/routes/*`) | `@RestController` (`controller/`) | Mapping, `@RequestBody`, `ResponseEntity` |
| Middleware tự viết (`lib/middleware.js`, `lib/auth.js`) | Spring Security + Spring Session Data Redis, filter `ApiFilters` (`security/`) | SecurityFilterChain, phiên lưu ở Redis |
| `shared/validate.mjs` (chạy cả ở giao diện) | `validation/*Validator` (bản Java của `frontend/src/shared/validate.mjs`) + Bean Validation (`@Valid`, `@StrongPassword`) | Ràng buộc tự viết |
| `setInterval` trong `lib/engine.js` | `@Scheduled` + `@SchedulerLock` (ShedLock trên Redis) (`EngineTicker`, `EngineLock`) | Lập lịch, khoá phân tán, virtual threads |
| Cache trong bộ nhớ (`lib/fb.js`) | Bộ nhớ + Redis qua Spring Cache (`CacheConfig`, `@Cacheable`, `@CacheEvict`) | Cache 2 tầng, TTL, serializer JSON |
| Đếm đăng nhập sai trong `Map` | Redis `INCR` + `EXPIRE` (`LoginAttempts`) | Đếm có hạn, dùng chung giữa nhiều bản |
| Giao diện tự tải lại mỗi 60 giây | WebSocket STOMP `/ws` + Redis pub/sub (`WebSocketConfig`, `LiveEvents`) | Đẩy sự kiện realtime, chạy được nhiều bản |
| `fetch` + tự thử lại (`lib/fb.js`) | `RestClient` + Resilience4j `@Retry` (`GraphClient`) | Client HTTP, retry có backoff |
| `/api/health` | Actuator `/actuator/health` | Theo dõi sức khoẻ ứng dụng |

## Bản Java chắc chắn hơn ở mấy chỗ

- **Chạy ngay** và vòng tự động dùng chung một khoá ShedLock trên Redis, nên không bao giờ chạy chồng lên nhau, kể cả khi chạy nhiều bản tool.
- Khởi động lại không bị đăng xuất, và số liệu camp lấy lại ngay từ Redis thay vì chờ gọi Facebook.
- Mỗi bước của vòng tự động có `try/catch` riêng: lịch lỗi thì rule và báo cáo vẫn chạy.
- Mỗi lần chạy lịch được "giữ chỗ" bằng khoá chính trong DB (`schedule_runs`), nên không chạy 2 lần kể cả khi khởi động lại giữa chừng.
- Dữ liệu ghi thẳng vào DB theo từng thay đổi, không còn cảnh ghi cả file hay đồng bộ chậm lên Upstash.

## Đưa lên mạng

- **Render không có MariaDB.** Có 3 cách:
  - app trên Render, DB ở dịch vụ ngoài (ví dụ Aiven MySQL gói free; đặt `DB_URL=jdbc:mariadb://...`);
  - chạy cả app và DB trên VPS bằng `docker compose` (file `docker-compose.yml` ở thư mục gốc);
  - dùng MySQL có sẵn ở chỗ khác.
- **Redis**: Compose đã có sẵn. Trên Render dùng Upstash (gói free): đặt `REDIS_URL` dạng `rediss://…` và `REDIS_CONFIGURE_ACTION=none`.
- Image đã giới hạn bộ nhớ JVM (`MaxRAMPercentage=70`, SerialGC), nên chạy vừa gói 512 MB. Đo thử được khoảng 340 MB.
- Build image từ thư mục gốc repo: `docker build -t fbads .`

## Giai đoạn tiếp theo (đã bàn)

1. ~~**Redis**~~ và ~~**WebSocket (STOMP)**~~: đã xong (giai đoạn 2).
2. **Kafka**:
   - phát sự kiện "đã tắt camp" và "đã đổi ngân sách";
   - chỉ chạy ở máy qua Compose, bật/tắt được;
   - khi tắt thì dùng Spring events thay thế.
