# FB Ads Auto (bản Java)

Tool tự động bật/tắt camp, chỉnh ngân sách Facebook Ads theo lịch và theo hiệu quả, báo cáo qua Telegram.

Nhánh này là bản học **Spring Boot**. Bản Node.js đang chạy thật nằm ở nhánh `dev`.

| Thư mục | Nội dung |
|---|---|
| `backend/` | Java 21 + Spring Boot 4, dữ liệu ở MariaDB, cache/phiên/realtime qua Redis, sự kiện qua Kafka (tuỳ chọn). Chi tiết trong [backend/README.md](backend/README.md) |
| `frontend/` | Vue 3 + Vite, nhận cập nhật realtime qua WebSocket |

## Chạy nhanh bằng Docker
```bash
APP_PASSWORD='MatKhau@2026' docker compose up --build
```
Mở http://localhost:3000 và đăng nhập bằng mật khẩu vừa đặt. Lệnh này build cả giao diện lẫn backend, kèm MariaDB, Redis và Kafka.

## Chạy khi đang code
Cần Java 21, Maven, Node 20+ và Docker (cho MariaDB và Redis).

1. Bật MariaDB và Redis (một lần):
   ```bash
   docker run -d --name fbads-db -p 127.0.0.1:3306:3306 \
     -e MARIADB_DATABASE=fbads -e MARIADB_USER=fbads -e MARIADB_PASSWORD=fbads -e MARIADB_ROOT_PASSWORD=root mariadb:11.8
   docker run -d --name fbads-redis -p 127.0.0.1:6379:6379 redis:7
   ```
2. Backend (cửa sổ 1):
   ```bash
   cd backend
   mvn spring-boot:run        # API ở http://127.0.0.1:3000
   ```
   Muốn thử Kafka: `docker run -d --name fbads-kafka -p 127.0.0.1:9092:9092 apache/kafka:4.2.0` rồi chạy backend với `KAFKA_ENABLED=true`
   (xem [backend/README.md](backend/README.md#sự-kiện-và-kafka)).
3. Frontend (cửa sổ 2):
   ```bash
   cd frontend
   npm install
   npm run dev                # mở http://localhost:5173, tự chuyển /api và /ws sang cổng 3000
   ```

Sửa giao diện là thấy ngay, không cần build. Muốn backend tự phục vụ giao diện ở cổng 3000 thì chạy `npm run build` trong `frontend/` (ra `frontend/dist`).

## Test
- Backend: `cd backend && mvn test`. Cần Docker, vì test bật MariaDB, MySQL 8, Redis và Kafka thật.
- Frontend: `cd frontend && npm test`. Kiểm tra các luật dùng chung ở `frontend/src/shared/` (kiểm tra dữ liệu, tiền, ngày…).

## Tính năng
- Tổng quan: bật/tắt camp, sửa ngân sách ngay trên dòng, xem chi tiêu/kết quả/CPA/ROAS hôm nay.
- Lịch: bật/tắt/đổi ngân sách theo giờ và ngày trong tuần, có dòng thời gian trong ngày.
- Rule: vd "CPA > 150.000 và đã chi ≥ 100.000 thì tắt", có trần/sàn ngân sách và thời gian nghỉ.
- Rule nâng cao: chọn khoảng thời gian (hôm nay/hôm qua/3 ngày/7 ngày), hành động **Chỉ thông báo**, nút **Xem trước** (rule đang khớp camp nào ngay bây giờ) trước khi cho chạy.
- Bảo vệ ngân sách: bỏ qua camp đang học, giới hạn thay đổi ngân sách mỗi ngày, dừng khẩn khi tổng chi tiêu vượt mức.
- Hoàn tác: đưa camp về trạng thái/ngân sách trước đó ngay từ Nhật ký (trong 3 ngày).
- Telegram: thông báo mỗi thay đổi + báo cáo hằng ngày.
- Nhật ký chi tiết: bấm một dòng để xem nguyên nhân lỗi, cách khắc phục, mã lỗi Facebook, yêu cầu đã gửi, trước/sau, điều kiện rule; có nút sao chép và chạy lại. Token không bao giờ được ghi vào nhật ký.
- Giao diện sáng/tối/theo hệ thống, 5 màu nhấn, thanh lệnh nhanh `Ctrl K`, dùng được trên điện thoại.
- Hướng dẫn ngay trong app: trang **Hướng dẫn** (tìm kiếm, FAQ, thuật ngữ), dấu `?` giải thích cạnh các ô khó, thẻ **Bắt đầu nhanh** tự tick theo tiến độ.
- Đăng nhập bằng mật khẩu (Cài đặt → Bảo mật, hoặc biến môi trường `APP_PASSWORD`).

## Đăng nhập bằng Facebook (lấy token bằng 1 nút)
Cài đặt → Kết nối Facebook → chọn **Đăng nhập Facebook**:
1. Trong ứng dụng Meta (loại Business), thêm sản phẩm **Facebook Login** rồi dán địa chỉ tool hiển thị (dạng `https://<tên miền>/api/fb/callback`) vào **Valid OAuth Redirect URIs**. Chạy trên máy thì mở tool bằng `http://localhost:3000` (không dùng `127.0.0.1`).
2. Nhập App ID + App Secret một lần (lưu cùng dữ liệu, không bao giờ trả về giao diện).
3. Bấm **Đăng nhập bằng Facebook**, cho phép `ads_management`, `ads_read`. Tool tự đổi sang token ~60 ngày. Hết hạn thì bấm lại.

Dùng **Facebook Login for Business** thì tạo một cấu hình có 2 quyền trên và nhập **Configuration ID**. Nếu địa chỉ tool tự nhận sai (đứng sau proxy lạ), đặt biến môi trường `PUBLIC_URL=https://ten-mien-cua-ban`.

## Đăng nhập bằng Facebook (lấy token bằng 1 nút)
Cài đặt → Kết nối Facebook → chọn **Đăng nhập Facebook**:
1. Trong ứng dụng Meta (loại Business), thêm sản phẩm **Facebook Login** rồi dán địa chỉ tool hiển thị (dạng `https://<tên miền>/api/fb/callback`) vào **Valid OAuth Redirect URIs**. Chạy trên máy thì mở tool bằng `http://localhost:3000` (không dùng `127.0.0.1`).
2. Nhập App ID + App Secret một lần (lưu cùng dữ liệu, không bao giờ trả về giao diện).
3. Bấm **Đăng nhập bằng Facebook**, cho phép `ads_management`, `ads_read`. Tool tự đổi sang token ~60 ngày. Hết hạn thì bấm lại.

Dùng **Facebook Login for Business** thì tạo một cấu hình có 2 quyền trên và nhập **Configuration ID**. Nếu địa chỉ tool tự nhận sai (đứng sau proxy lạ), đặt biến môi trường `PUBLIC_URL=https://ten-mien-cua-ban`.

## An toàn
- Mặc định ở chế độ dùng thử (dữ liệu giả). Khi kết nối thật, mặc định "Chạy thử" (chỉ ghi log). Tắt sau vài ngày khi thấy log đúng ý.
- Server mặc định chỉ nghe 127.0.0.1. Mở ra mạng (`HOST=0.0.0.0`, như trong Docker) thì bắt buộc có `APP_PASSWORD`.
- Token và mật khẩu (đã băm) nằm trong bảng `app_settings` của MariaDB. Không chia sẻ bản sao lưu DB.
- Quên mật khẩu đặt trong Cài đặt: chạy `UPDATE app_settings SET password_hash='';` trong MariaDB rồi khởi động lại tool.
- Nếu camp dùng CBO (ngân sách ở cấp camp), chỉnh ngân sách ở cấp camp; nhóm QC sẽ không có ngân sách riêng.
