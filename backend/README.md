# Backend (Spring Boot)

Bản chuyển backend Node (`server.js`, `lib/`) sang **Java 21 + Spring Boot 4**, dữ liệu lưu ở **MySQL 8**, cache/phiên đăng nhập/khoá/sự kiện realtime ở **Redis**,
sự kiện (thông báo, thống kê, cập nhật giao diện) đi qua **Kafka** khi bật.
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
| `DB_URL` | `jdbc:mysql://localhost:3306/fbads` | Địa chỉ MySQL 8.0.13+ (xem dưới) |
| `DB_USER`, `DB_PASSWORD` | `fbads` | Tài khoản DB |
| `REDIS_URL` | `redis://localhost:6379` | Địa chỉ Redis (bắt buộc). Upstash: `rediss://default:MẬT_KHẨU@xxx.upstash.io:6379` |
| `REDIS_CONFIGURE_ACTION` | `notify-keyspace-events` | Đặt `none` khi Redis dịch vụ không cho lệnh `CONFIG` (Upstash, ElastiCache) |
| `PORT`, `HOST` | `3000`, `127.0.0.1` | Cổng và địa chỉ nghe |
| `APP_PASSWORD` | | Mật khẩu của tài khoản `admin` (tạo nếu chưa có); bắt buộc khi `HOST` không phải máy này |
| `SECRET_KEY` | | Khoá mã hoá token trong DB (AES-256-GCM). Chưa đặt thì lưu nguyên văn và có cảnh báo lúc khởi động. Đặt rồi thì không được đổi |
| `ALLOW_SIGNUP` | `false` | `true`: trang đăng nhập có nút tự đăng ký, mỗi người đăng ký có workspace riêng |
| `PUBLIC_URL` | | Địa chỉ công khai, dùng cho đăng nhập Facebook |
| `PUBLIC_DIR` | `../frontend/dist` | Thư mục giao diện đã build |
| `ENGINE_ENABLED` | `true` | Tắt vòng chạy lịch/rule (dùng khi test) |
| `KAFKA_ENABLED` | `false` | `true`: sự kiện đi qua Kafka (xem dưới). Giá trị khác: đi bằng Spring events, không cần Kafka |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Địa chỉ Kafka, chỉ dùng khi `KAFKA_ENABLED=true` |
| `SPRING_PROFILES_ACTIVE` | | `dev`: bật log chi tiết ở mọi tầng và log SQL (xem "Xem log chi tiết") |
| `LOG_CONTROLLER`, `LOG_SERVICE`, `LOG_ENGINE`, `LOG_REPOSITORY`, `LOG_SQL` | `false` (profile `dev`: `true`) | Bật/tắt log chi tiết từng tầng |

### MySQL

- Driver là MySQL Connector/J, địa chỉ dạng `jdbc:mysql://máy:3306/fbads`. Tool tự thêm `allowPublicKeyRetrieval=true`
  (MySQL 8 đăng nhập bằng `caching_sha2_password`) và giờ UTC, nên không cần gắn tham số vào `DB_URL`.
- Bản Java không còn chạy trên MariaDB. `DB_URL` cũ dạng `jdbc:mariadb://…` phải đổi thành `jdbc:mysql://…`.
- DB MySQL đã tạo từ trước (bằng driver MariaDB) dùng tiếp được, không phải xoá tạo lại.
- Lần chạy trước bị lỗi migration thì xoá DB tạo lại cho sạch: `DROP DATABASE fbads; CREATE DATABASE fbads;`.

## Sự kiện và Kafka

Mỗi thay đổi là một sự kiện (`event/AppEvent`), phát một lần qua `EventBus`, rồi 3 nơi nhận độc lập:

| Consumer | Làm gì |
|---|---|
| Thông báo (`notify/Notifier`) | Gửi tin cho lịch, rule, dừng khẩn, hoàn tác, cảnh báo, báo cáo tới các kênh của workspace (Telegram, Gmail…). Thao tác tay không báo |
| Thống kê (`EventStatsService`) | Đếm số thao tác theo ngày và nguồn (`schedule`, `rule`, `manual`…) vào bảng `event_stats` |
| Giao diện (`LiveEvents`) | Đẩy nhật ký, camp vừa đổi, lượt tự động xuống trình duyệt qua WebSocket |

Loại sự kiện: `log.created`, `log.updated`, `objects.changed`, `engine.tick`, `report.daily`, `notice` (thông báo soạn sẵn).

**`KAFKA_ENABLED=false`** (mặc định, và trên Render): sự kiện đi bằng Spring events trong cùng ứng dụng, giao diện nhận qua Redis pub/sub. Không cần Kafka.

**`KAFKA_ENABLED=true`**:
- Mọi sự kiện lên topic `fbads.events` (3 partition, nội dung JSON). Sự kiện nhật ký dùng chung khoá `logs` nên đến đúng thứ tự.
- Mỗi consumer một group: `fbads-telegram`, `fbads-stats`, và `fbads-live-<ngẫu nhiên>` (mỗi bản tool một group, không lưu vị trí đã đọc).
- Group `fbads-telegram` là consumer thông báo (giữ tên cũ: đổi tên thì Kafka đọc lại từ đầu topic và gửi lại tin cũ).
- Một kênh lỗi tạm thời (mất mạng, 429, 5xx): thử lại cả sự kiện qua topic `fbads.events-notify-retry-0`, `-1`, `-2` (chờ 5 giây, 15 giây, 45 giây),
  hết lượt thì vào `fbads.events-notify-dlt`. Lỗi cố định (token, người nhận sai) vào thẳng DLT.
- Kafka có thể giao một sự kiện 2 lần: thống kê ghi mã sự kiện đã đếm (`event_stats_seen`), thông báo đánh dấu đã gửi trong Redis theo từng kênh (`fbads:notify:<sự kiện>:<kênh>`, giữ 2 ngày). Nhận lại, hoặc thử lại vì kênh khác lỗi, thì kênh đã gửi được bỏ qua.
- Kafka chưa chạy lúc khởi động: tool dừng và in thông báo tiếng Việt (mục "APPLICATION FAILED TO START") nói cách tắt Kafka hoặc bật Kafka (`event/KafkaStartupFailure`). Kafka tắt giữa chừng: tool vẫn chạy bình thường, chỉ mất thông báo và cập nhật tức thì trong lúc đó (dữ liệu vẫn ở DB).
  Gửi lên Kafka chạy ở luồng riêng nên thao tác trên giao diện không bị chậm.

### Bật Kafka khi đang code

```bash
docker run -d --name fbads-kafka -p 127.0.0.1:9092:9092 apache/kafka:4.2.0
```

Rồi chạy backend với `KAFKA_ENABLED=true` (IntelliJ: Run → Edit Configurations → Environment variables). Kafka ở `localhost:9092` nên không cần đặt `KAFKA_BOOTSTRAP_SERVERS`.
Log khởi động có dòng `fbads-telegram: partitions assigned` là đã nối được.

Xem sự kiện đang chạy qua topic, và thống kê:

```bash
docker exec -it fbads-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic fbads.events --from-beginning
docker exec -it fbads-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic fbads.events-notify-dlt --from-beginning
```

```sql
SELECT * FROM event_stats ORDER BY day DESC, source;
```

Chạy bằng `docker compose` thì Kafka đã bật sẵn, và nghe thêm ở `localhost:9094` để xem topic từ ngoài Docker. Tắt Kafka: `KAFKA_ENABLED=false docker compose up`.

## Kênh thông báo (Telegram, Gmail…)

Hình dung như phòng thư của văn phòng: nơi phát (lịch, rule, cảnh báo, báo cáo công ty) chỉ **viết thư** (`Notice`: loại tin + nội dung),
**phòng thư** (`Notifier`) xem workspace đã cài những kênh nào nhận loại tin đó rồi giao cho từng **hãng chuyển phát** (`NotifyChannel`).

```
nơi phát ── events.notify("alerts", new Notice(ALERT, "⚠️ <b>…</b>")) ──▶ sự kiện "notice" (Kafka hoặc Spring events)
                                                                              │
Notifier.handleOnce(sự kiện) ── kênh nào của workspace nhận ALERT? (bảng notify_targets)
     ├─▶ TelegramChannel.send(notice, cấu hình nhóm A)
     └─▶ EmailChannel.send(notice, cấu hình email sếp)
```

- `Notice.html` viết bằng HTML rút gọn như Telegram (`<b> <i> <a>`); dòng đầu là tiêu đề. Telegram gửi nguyên văn, Gmail dùng dòng đầu làm tiêu đề thư.
- Loại tin (`Notice.Topic`): `LOG` nhật ký tự động, `ALERT` cảnh báo, `REPORT` báo cáo hằng ngày/tuần, `COMPANY` báo cáo công ty. Mỗi kênh chọn loại tin muốn nhận.
- Kênh đã cài nằm ở bảng `notify_targets` (cấu hình JSON, mã hoá cả chuỗi bằng `SECRET_KEY`). Giao diện: Cài đặt → Thông báo.

**Thêm một loại kênh mới** (vd Zalo): viết một class `@Component` cài `NotifyChannel` trong `notify/channel/`, gồm
`type()`, `label()`, `fields()` (các ô cấu hình, ô bí mật đánh `secret`), `validate()` và `send()`. Không phải sửa file nào khác:
Spring tự gom class vào `Notifier`, và form "Thêm kênh" trên giao diện tự vẽ theo `fields()`. Xem `EmailChannel` làm mẫu.

## Xem log chi tiết

Để học và gỡ lỗi: xem method nào được gọi, với tham số gì, trả về gì, mất bao lâu, và câu SQL nào chạy xuống DB.
Nhanh nhất là chạy với profile `dev` (IntelliJ: Environment variables `SPRING_PROFILES_ACTIVE=dev`), profile này bật hết:

```
fbads.calls.controller : → ObjectsController.budget(id="mock_1", b={"amount":510000,"name":"Camp 1"})
fbads.calls.service    :   → ObjectService.setBudget(id="mock_1", amount=510000.0, name="Camp 1")
fbads.calls.repository :     → LogRepository.save(entity={"kind":"manual","name":"Camp 1",…})
org.hibernate.SQL      :       insert into logs (…) values (?, ?, …)
org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [manual]
fbads.calls.repository :     ← LogRepository.save 6 ms: {…}
fbads.calls.service    :   ← ObjectService.setBudget 18 ms
```

| Cờ | Tầng | Ghi gì |
|---|---|---|
| `LOG_CONTROLLER` | `@RestController` | Request vào API nào, tham số |
| `LOG_SERVICE` | `@Service` | Nghiệp vụ, Facebook, sự kiện |
| `LOG_ENGINE` | `engine/`, `*Runner`, `RuleEvaluator`, `AlertWatch` | Vòng tự động mỗi 30 giây (bỏ qua `EngineClock` vì chỉ đọc giờ) |
| `LOG_REPOSITORY` | `*Repository` | Method repository được gọi (kể cả `save`, `findById`) và thời gian |
| `LOG_SQL` | Hibernate | Câu SQL (xuống dòng cho dễ đọc) và giá trị từng tham số `?` |

- Bật/tắt riêng từng tầng bằng biến môi trường. Ví dụ dùng `dev` nhưng thấy vòng tự động làm rối log thì thêm `LOG_ENGINE=false`.
  Không dùng `dev` mà chỉ muốn xem SQL thì đặt `LOG_SQL=true`.
- Mỗi lần gọi ghi 2 dòng: `→` lúc vào, `←` lúc ra (kèm số ms và kết quả), `✗` nếu lỗi. Lời gọi lồng nhau thụt lề vào trong.
- Tham số có tên chứa password, token, secret (và `pw`, `code`) in ra `***`. Giá trị dài quá 300 ký tự bị cắt, danh sách chỉ in 5 phần tử đầu.
- Câu SQL chạy lâu hơn 100 ms có thêm dòng `Slow query took … milliseconds` (chỉ trong profile `dev`).
- **Lưu ý**: log SQL in nguyên giá trị tham số, kể cả token Facebook / kênh thông báo khi lưu. Chỉ bật trên máy mình, đừng bật trên Render hay VPS.
- Mã nằm ở `logging/` (`CallLogging` là các aspect, `CallLogger` định dạng dòng log). Tầng nào tắt thì aspect của tầng đó không được tạo.

## Test

```bash
mvn test        # cần Docker: Testcontainers tự bật MySQL 8.0, Redis và Kafka thật
```

- **NodeCompatTest** kiểm tra hai thứ do bản Node tạo ra (trong `src/test/resources/fixtures/`):
  - mật khẩu băm scrypt vẫn đăng nhập được;
  - dữ liệu mã hoá trên Upstash giải mã được.
- **ApiIntegrationTest** gọi API y như giao diện:
  - 20 ca kiểm tra lịch/rule cho kết quả giống hệt `frontend/src/shared/validate.mjs`;
  - lịch, thao tác tay, hoàn tác;
  - đăng nhập và các lớp chặn;
  - tài khoản, workspace tách dữ liệu, vai trò Chủ/Biên tập/Chỉ xem bị chặn đúng chỗ;
  - token trong DB đã được mã hoá;
  - số liệu Facebook được cache ở Redis và bị xoá khi đổi ngân sách;
  - vòng tự động và nút "Chạy ngay" dùng chung khoá ShedLock;
  - phiên đăng nhập ở Redis, nhập sai 5 lần thì bị khoá 15 phút;
  - client STOMP nhận nhật ký và sự kiện camp ngay khi đổi ngân sách; chưa đăng nhập thì bị từ chối;
  - sự kiện khi tắt Kafka: thông báo, thống kê, báo cáo hằng ngày;
  - giờ lưu trong MySQL là UTC.
- **KafkaEventsTest** bật Kafka thật:
  - một sự kiện tới đủ thông báo, thống kê và WebSocket;
  - kênh lỗi tạm thời được thử lại rồi vào DLT, lỗi cố định vào thẳng DLT; 2 kênh mà 1 kênh lỗi thì chỉ kênh lỗi được gửi lại;
  - sự kiện nhận 2 lần chỉ đếm và gửi thông báo 1 lần; bản ghi hỏng bị bỏ qua ngay;
  - báo cáo hằng ngày và thao tác đổi ngân sách đi qua Kafka.
- **KafkaEventSenderTest**: Kafka không chạy thì nơi phát sự kiện không phải chờ.
- **EmailChannelTest**: kênh Gmail gửi tới máy chủ SMTP giả (GreenMail): mỗi người một thư, sai mật khẩu ứng dụng là lỗi cố định, mất mạng là lỗi tạm thời.
- **NotifyApiTest**: thêm/sửa/xoá/gửi thử kênh thông báo, ô bí mật không trả về giao diện, kênh chỉ nhận loại tin đã chọn, chuyển Telegram kiểu cũ sang bảng mới.
- **SecretConverterTest**: mã hoá/giải mã token, sai khoá thì báo lỗi rõ ràng.
- **CallLoggingTest**, **CallLoggerTest**: log chi tiết đủ các tầng và SQL, tầng tắt thì im lặng, không lộ mật khẩu/token.
- **ImportIntegrationTest** khởi động với `data.json` mẫu, rồi kiểm tra dữ liệu đã vào DB.

## Cấu trúc thư mục (chia theo tính năng)

Mỗi tính năng một package, trong đó có đủ controller, service, entity, repository, DTO (record) và validator của nó. Muốn hiểu "lịch" thì chỉ mở `schedule/`. Trong mỗi package, đọc theo tên: `*Controller` (API) → `*Service` (nghiệp vụ) → entity (bảng) → `*Repository`. Mỗi package có `package-info.java` nói nên đọc file nào trước.

| Package | Tính năng |
|---|---|
| `schedule/` | Lịch tự động: `ScheduleController`, `ScheduleService`, `ScheduleValidator`, `Schedule` (+ `ScheduleFilter`, `ScheduleWindow`), `ScheduleRunner` (engine gọi), `BulkFilter` |
| `rule/` | Rule: `RuleController`, `RuleService`, `RuleValidator`, `Rule`, `Condition`, `RuleRunner` (engine gọi), `RuleEvaluator` (chỉ tính toán), `RulePreview`, `RuleActivity` |
| `log/` | Nhật ký và hoàn tác: `LogController`, `LogService`, `UndoService`, `LogEntry` và các phần JSON (`LogChange`, `LogSnapshot`…) |
| `ads/` | Camp/nhóm QC: `ObjectsController`, `ObjectService` (bật/tắt, ngân sách tay, xu hướng), `AdObject`, `Metrics` |
| `facebook/` | Graph API: `FacebookObjects`, `FacebookInsights`, `FacebookActions`, `FacebookAuth`, `FacebookHealth` (+ `FacebookGraph`, `FacebookState`, `FacebookParse` dùng chung), `GraphClient` (HTTP, thử lại, giới hạn gọi), `MockAds` (dữ liệu giả), `FacebookController` |
| `account/` | Người dùng, workspace, thành viên, vai trò, đăng nhập: `AuthController`, `WorkspaceController`, `AuthService`, `LoginAttempts` |
| `settings/` | Cài đặt workspace: `SettingsController`, `SettingsService`, `SettingsValidator`, `AppSettings`, `SettingsPatch`, `DataImporter` |
| `report/` | Báo cáo ngày/tuần và cảnh báo bất thường: `ReportController`, `ReportService`, `AlertWatch` |
| `company/` | Báo cáo lên hệ thống công ty: `CompanyController`, `CompanyReportService`, `CompanyRules`, `CompanyApi`… |
| `notify/` | Gửi thông báo, không phụ thuộc kênh: `Notice` (nội dung), `NotifyChannel` (hợp đồng chung của mọi kênh), `Notifier` (chọn kênh của workspace rồi giao), `NotifyTargetService`, `NotifyController`. Mỗi loại kênh một class trong `notify/channel/` (`TelegramChannel`, `EmailChannel`) |
| `engine/` | Lõi vòng tự động mỗi 30 giây: `EngineTicker`, `ActionExecutor` (nơi duy nhất làm thay đổi thật), `EngineState`, `EngineClock`, `KillSwitch` |
| `event/` | Sự kiện: `EventBus`, bản Kafka (`KafkaEvents`, `KafkaEventListeners`) và bản Spring events (`LocalEvents`), `LiveEvents` (WebSocket), `EventStatsService` |
| `web/` | HTTP dùng chung: `ApiExceptionHandler` (lỗi → JSON), `HealthController`, `Ok`, `ApiError`, `Saved` |
| `security/` | Spring Security, mã hoá mật khẩu, filter chặn request lạ, `WorkspaceContext` + `WorkspaceFilter` (workspace và vai trò của từng request) |
| `logging/` | Log chi tiết từng tầng bằng Spring AOP (`@Aspect`, `@Around`) |
| `config/`, `common/` | Cấu hình Spring (Redis, cache, WebSocket, Jackson…) và tiện ích dùng chung (`Json`, `Fmt`, `Checks`, `Result`…) |

## Đối chiếu Node → Spring (để học)

| Bản Node | Bản Java | Học được gì |
|---|---|---|
| `data.json` / Upstash (`lib/store.js`) | MySQL + Spring Data JPA (`*Repository`), Flyway | Entity, repository, migration, `@Version` |
| `express.Router` (`lib/routes/*`) | `@RestController` (`*Controller` trong từng package tính năng) | Mapping, `@RequestBody`, `ResponseEntity` |
| `req.body` (object tuỳ ý) | Body đọc thẳng vào record (DTO nằm cùng package tính năng); số dạng chữ đọc như `Number()` của JS nhờ `@JsNumber`; sai kiểu → 400 `"Sai kiểu dữ liệu"` kèm tên trường | DTO, record, deserializer tự viết, PATCH phân biệt "không gửi" với "gửi null" (`SettingsPatch`) |
| Middleware tự viết (`lib/middleware.js`, `lib/auth.js`) | Spring Security + Spring Session Data Redis, filter `ApiFilters` (`security/`) | SecurityFilterChain, phiên lưu ở Redis |
| `shared/validate.mjs` (chạy cả ở giao diện) | `*Validator` (`schedule/`, `rule/`, `settings/`; bản Java của `frontend/src/shared/validate.mjs`) + Bean Validation (`@Valid`, `@StrongPassword`) | Ràng buộc tự viết |
| `setInterval` trong `lib/engine.js` | `@Scheduled` + `@SchedulerLock` (ShedLock trên Redis) (`EngineTicker`, `EngineLock`) | Lập lịch, khoá phân tán, virtual threads |
| Cache trong bộ nhớ (`lib/fb.js`) | Bộ nhớ + Redis qua Spring Cache (`CacheConfig`, `@Cacheable`, `@CacheEvict`) | Cache 2 tầng, TTL, serializer JSON |
| Đếm đăng nhập sai trong `Map` | Redis `INCR` + `EXPIRE` (`LoginAttempts`) | Đếm có hạn, dùng chung giữa nhiều bản |
| Giao diện tự tải lại mỗi 60 giây | WebSocket STOMP `/ws` + Redis pub/sub (`WebSocketConfig`, `LiveEvents`) | Đẩy sự kiện realtime, chạy được nhiều bản |
| Gọi Telegram ngay trong engine (`lib/engine.js`) | Sự kiện qua Kafka: topic, consumer group, `@RetryableTopic` + DLT (`event/`) | Producer/consumer, thử lại bằng topic riêng, consumer idempotent |
| `fetch` + tự thử lại (`lib/fb.js`) | `RestClient` + Resilience4j `@Retry` (`GraphClient`) | Client HTTP, retry có backoff |
| `console.log` rải rác | Aspect `@Around` bọc mọi method của một tầng (`logging/CallLogging`) | AOP, pointcut, proxy |
| `/api/health` | Actuator `/actuator/health` | Theo dõi sức khoẻ ứng dụng |

## Bản Java chắc chắn hơn ở mấy chỗ

- **Chạy ngay** và vòng tự động dùng chung một khoá ShedLock trên Redis, nên không bao giờ chạy chồng lên nhau, kể cả khi chạy nhiều bản tool.
- Khởi động lại không bị đăng xuất, và số liệu camp lấy lại ngay từ Redis thay vì chờ gọi Facebook.
- Mỗi bước của vòng tự động có `try/catch` riêng: lịch lỗi thì rule và báo cáo vẫn chạy.
- Mỗi lần chạy lịch được "giữ chỗ" bằng khoá chính trong DB (`schedule_runs`), nên không chạy 2 lần kể cả khi khởi động lại giữa chừng.
- Dữ liệu ghi thẳng vào DB theo từng thay đổi, không còn cảnh ghi cả file hay đồng bộ chậm lên Upstash.

## Đưa lên mạng

- **Render không có MySQL.** Có 3 cách:
  - app trên Render, DB ở dịch vụ ngoài (ví dụ Aiven MySQL gói free; đặt `DB_URL=jdbc:mysql://...`);
  - chạy cả app và DB trên VPS bằng `docker compose` (file `docker-compose.yml` ở thư mục gốc);
  - dùng MySQL có sẵn ở chỗ khác.
- **Redis**: Compose đã có sẵn. Trên Render dùng Upstash (gói free): đặt `REDIS_URL` dạng `rediss://…` và `REDIS_CONFIGURE_ACTION=none`.
- **Kafka**: chỉ chạy qua Compose. Trên Render để `KAFKA_ENABLED` mặc định (`false`), tool vẫn đủ tính năng.
- Image đã giới hạn bộ nhớ JVM (`MaxRAMPercentage=70`, SerialGC), nên chạy vừa gói 512 MB. Đo thử được khoảng 340 MB.
- Build image từ thư mục gốc repo: `docker build -t fbads .`

## Giai đoạn tiếp theo (đã bàn)

1. ~~**Redis**~~ và ~~**WebSocket (STOMP)**~~: đã xong (giai đoạn 2).
2. ~~**Kafka**~~: đã xong (giai đoạn 3), xem mục "Sự kiện và Kafka".

## Nhiều người dùng (workspace)

- Bảng `users`, `workspaces`, `workspace_members` (migration `V5__workspaces.sql`). Mọi bảng dữ liệu có cột `workspace_id`; dữ liệu cũ vào workspace 1.
- Entity đánh dấu `@TenantId` (Hibernate): mọi truy vấn tự thêm `workspace_id = ?`, lấy từ `WorkspaceContext` qua `TenantConfig`. Không cần sửa từng repository.
- `WorkspaceFilter` đặt workspace cho mỗi request (lưu trong phiên, đổi bằng `POST /api/workspaces/switch`) và chặn theo vai trò: Chỉ xem không gọi được lệnh ghi, chỉ Chủ vào được cài đặt/Facebook/kênh thông báo/thành viên.
- `EngineTicker` chạy từng workspace một, mỗi workspace một khoá ShedLock `fbads-engine-ws-{id}`. Sự kiện Kafka mang theo workspace; WebSocket gửi tới `/topic/ws.{id}.logs|objects|engine` và chỉ thành viên được nghe.
- API mới: `POST /api/setup` (tài khoản đầu tiên), `POST /api/register` (khi `ALLOW_SIGNUP`), `GET/POST /api/workspaces`, `POST /api/workspace` (đổi tên), `GET/POST /api/members`, `POST /api/members/{id}/role`, `DELETE /api/members/{id}`. `POST /api/login` nhận `{username, password}` (bỏ trống username = `admin`).
