# FB Ads Auto (bản Java)

Tool tự động bật/tắt camp, chỉnh ngân sách Facebook Ads theo lịch và theo hiệu quả, báo cáo qua Telegram.

Nhánh này là bản học **Spring Boot**. Bản Node.js đang chạy thật nằm ở nhánh `dev`.

| Thư mục | Nội dung |
|---|---|
| `backend/` | Java 21 + Spring Boot 4, dữ liệu ở MySQL 8, cache/phiên/realtime qua Redis, sự kiện qua Kafka (tuỳ chọn). Chi tiết trong [backend/README.md](backend/README.md) |
| `frontend/` | Vue 3 + Vite, nhận cập nhật realtime qua WebSocket |

## Chạy nhanh bằng Docker
```bash
APP_PASSWORD='MatKhau@2026' SECRET_KEY='mot-chuoi-dai-ngau-nhien' docker compose up --build
```
Mở http://localhost:3000 và đăng nhập tài khoản `admin` với mật khẩu vừa đặt. Lệnh này build cả giao diện lẫn backend, kèm MySQL, Redis và Kafka.

## Chạy khi đang code
Cần Java 21, Maven, Node 20+ và Docker (cho MySQL và Redis). Đã cài sẵn MySQL 8 trên máy thì dùng luôn, bỏ lệnh `docker run` đầu tiên.

1. Bật MySQL và Redis (một lần):
   ```bash
   docker run -d --name fbads-db -p 127.0.0.1:3306:3306 \
     -e MYSQL_DATABASE=fbads -e MYSQL_USER=fbads -e MYSQL_PASSWORD=fbads -e MYSQL_ROOT_PASSWORD=root mysql:8.0
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
GitHub Actions (`.github/workflows/ci.yml`) chạy cả hai phần dưới cho mỗi lần push lên `java-spring`.

- Backend: `cd backend && mvn test`. Cần Docker, vì test bật MySQL 8.0, Redis và Kafka thật. Facebook, Telegram và hệ thống công ty đều là máy chủ giả trong test.
- Frontend: `cd frontend && npm test`. Kiểm tra các luật dùng chung ở `frontend/src/shared/` (kiểm tra dữ liệu, tiền, ngày…).

## Tính năng
- Tổng quan: bật/tắt camp, sửa ngân sách ngay trên dòng, xem chi tiêu/kết quả/CPA/ROAS hôm nay.
- Lịch: bật/tắt/đổi ngân sách theo giờ và ngày trong tuần, có dòng thời gian trong ngày.
- Rule: vd "CPA > 150.000 và đã chi ≥ 100.000 thì tắt", có trần/sàn ngân sách và thời gian nghỉ.
- Rule nâng cao: chọn khoảng thời gian (hôm nay/hôm qua/3 ngày/7 ngày), hành động **Chỉ thông báo**, nút **Xem trước** (rule đang khớp camp nào ngay bây giờ) trước khi cho chạy.
- Bảo vệ ngân sách: bỏ qua camp đang học, giới hạn thay đổi ngân sách mỗi ngày, dừng khẩn khi tổng chi tiêu vượt mức.
- Hoàn tác: đưa camp về trạng thái/ngân sách trước đó ngay từ Nhật ký (trong 3 ngày).
- Telegram: thông báo mỗi thay đổi + báo cáo hằng ngày.
- Rule theo kết quả: so với khoảng trước (vd CPA hôm nay so với 7 ngày trước), ngưỡng chi tiêu theo số kết quả (bậc), tăng ngân sách theo thang bậc kết quả.
- Cảnh báo bất thường qua Telegram: tài khoản quảng cáo bị khoá/nợ, quảng cáo bị từ chối, chi tiêu tăng vọt theo giờ.
- Xu hướng theo ngày của từng camp và báo cáo tuần gửi Telegram sáng thứ Hai.
- Báo cáo công ty: tạo báo cáo theo mốc 9h/12h/17h/22h cho từng Team từ số Facebook, sửa số tay rồi gửi lên hệ thống báo cáo của công ty
  (chế độ Chỉ xem / Duyệt rồi gửi / Tự động gửi). Mốc 9h cập nhật số cả ngày hôm trước vào bản ghi 9h của hôm trước. Cài ở Cài đặt → Báo cáo công ty; mật khẩu công ty mã hoá bằng `SECRET_KEY`.
- Nhật ký chi tiết: bấm một dòng để xem nguyên nhân lỗi, cách khắc phục, mã lỗi Facebook, yêu cầu đã gửi, trước/sau, điều kiện rule; có nút sao chép và chạy lại. Token không bao giờ được ghi vào nhật ký.
- Giao diện sáng/tối/theo hệ thống, 5 màu nhấn, thanh lệnh nhanh `Ctrl K`, dùng được trên điện thoại.
- Hướng dẫn ngay trong app: trang **Hướng dẫn** (tìm kiếm, FAQ, thuật ngữ), dấu `?` giải thích cạnh các ô khó, thẻ **Bắt đầu nhanh** tự tick theo tiến độ.
- Nhiều người dùng: mỗi người một tài khoản, dữ liệu chia theo **workspace** (xem dưới).

## Nhiều người dùng và workspace
Mỗi workspace có cài đặt, token Facebook, Telegram, lịch, rule, nhật ký và vòng tự động riêng. Một người có thể ở nhiều workspace và đổi qua lại ở ô chọn trên thanh bên.

- **Chưa có tài khoản nào**: tool mở, không cần đăng nhập. Tạo tài khoản đầu tiên ở Cài đặt → Bảo mật, hoặc đặt `APP_PASSWORD` (tạo/đồng bộ tài khoản `admin`). Người tạo đầu tiên là chủ workspace có sẵn (dữ liệu cũ nằm ở đây).
- **Vai trò** (Cài đặt → Thành viên):
  - Chủ: toàn quyền, kể cả token Facebook, Telegram, thành viên.
  - Biên tập: sửa lịch, rule, bật/tắt camp, đổi ngân sách, hoàn tác.
  - Chỉ xem: xem số liệu, lịch, rule, nhật ký.
- Chủ thêm thành viên bằng tên đăng nhập; người chưa có tài khoản thì đặt kèm mật khẩu ban đầu. Muốn cho người lạ tự đăng ký thì đặt `ALLOW_SIGNUP=true` (người tự đăng ký tạo workspace riêng của họ).
- Mỗi workspace phải kết nối token Facebook riêng. Cho khách ngoài dùng token của họ qua ứng dụng Meta của bạn thì ứng dụng cần qua **App Review** quyền `ads_management`.

## Đăng nhập bằng Facebook (lấy token bằng 1 nút)
Cài đặt → Kết nối Facebook → chọn **Đăng nhập Facebook**:
1. Trong ứng dụng Meta (loại Business), thêm sản phẩm **Facebook Login** rồi dán địa chỉ tool hiển thị (dạng `https://<tên miền>/api/fb/callback`) vào **Valid OAuth Redirect URIs**. Chạy trên máy thì mở tool bằng `http://localhost:3000` (không dùng `127.0.0.1`).
2. Nhập App ID + App Secret một lần (lưu cùng dữ liệu, không bao giờ trả về giao diện).
3. Bấm **Đăng nhập bằng Facebook**, cho phép `ads_management`, `ads_read`. Tool tự đổi sang token ~60 ngày. Hết hạn thì bấm lại.

Dùng **Facebook Login for Business** thì tạo một cấu hình có 2 quyền trên và nhập **Configuration ID**. Nếu địa chỉ tool tự nhận sai (đứng sau proxy lạ), đặt biến môi trường `PUBLIC_URL=https://ten-mien-cua-ban`.

## An toàn
- Mặc định ở chế độ dùng thử (dữ liệu giả). Khi kết nối thật, mặc định "Chạy thử" (chỉ ghi log). Tắt sau vài ngày khi thấy log đúng ý.
- Server mặc định chỉ nghe 127.0.0.1. Mở ra mạng (`HOST=0.0.0.0`, như trong Docker) thì bắt buộc có `APP_PASSWORD`.
- Đặt `SECRET_KEY` (chuỗi dài ngẫu nhiên): token Facebook, App Secret và token Telegram được mã hoá AES-256-GCM trước khi ghi vào DB. Giữ nguyên `SECRET_KEY` mãi về sau; mất hoặc đổi khoá thì phải kết nối lại Facebook/Telegram. Mật khẩu người dùng nằm ở bảng `users` (đã băm bcrypt).
- Quên mật khẩu: tài khoản `admin` thì đổi `APP_PASSWORD` rồi khởi động lại. Tài khoản khác chưa có nút đặt lại; tạm thời `admin` tạo tài khoản mới cho người đó rồi thêm vào workspace.
- Nếu camp dùng CBO (ngân sách ở cấp camp), chỉnh ngân sách ở cấp camp; nhóm QC sẽ không có ngân sách riêng.
