# FB Ads Auto

Tool tự động bật/tắt camp, chỉnh ngân sách Facebook Ads theo lịch và theo hiệu quả, báo cáo qua Telegram.
Backend Node.js (không cần cài thư viện), giao diện Vue 3 + Vite.

## Chạy
Bấm đúp `start.bat` (hoặc `node server.js`) rồi mở http://localhost:3000.
Giữ cửa sổ mở (máy bật) để lịch tự chạy. Muốn tự khởi động cùng Windows: đặt shortcut `start.bat` vào `shell:startup`.

Giao diện đã được build sẵn trong `public/`, dùng tool **không cần** cài Vue hay npm.

## Phát triển giao diện (Vue 3 + Vite)
```bash
cd web
npm install
npm run dev        # http://localhost:5173 (tự proxy /api sang server ở cổng 3000)
npm run build      # build ra ../public
```
Chạy `node server.js` ở một cửa sổ khác khi dùng `npm run dev`. Cấu trúc `web/src`: `views/` (các trang), `components/` (thành phần dùng chung), `stores/` (trạng thái), `lib/` (api, định dạng), `styles/` (design token sáng/tối).

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

## Chia sẻ tạm thời qua ngrok
1. Cài một lần: `winget install ngrok.ngrok` rồi `ngrok config add-authtoken <AUTHTOKEN>` (lấy token ở ngrok.com).
2. Bấm đúp **`start-ngrok.bat`**: hỏi mật khẩu đăng nhập (ẩn ký tự), khởi động tool, mở đường hầm, in địa chỉ `https://…` và sao chép vào clipboard. Nhấn phím bất kỳ để dừng cả hai.
3. Muốn link cố định: tạo tên miền tĩnh miễn phí trong Dashboard ngrok rồi ghi tên miền (một dòng) vào file `ngrok-domain.txt` cạnh file .bat (file này đã nằm trong `.gitignore`).

An toàn: file .bat từ chối mở đường hầm nếu đang có một bản tool chạy sẵn mà **không có mật khẩu**. Máy phải bật thì link mới dùng được; dùng lâu dài nên chuyển sang VPS.

## Nơi lưu dữ liệu
Tool có 2 chế độ lưu, chọn bằng biến môi trường:
- **File `data.json`** (mặc định) trong `DATA_DIR`. Mỗi ngày tự giữ 1 bản sao `data.json.bak-<ngày>` (7 bản gần nhất). Nếu file bị hỏng, tool **không ghi đè**: đổi tên file hỏng thành `data.json.corrupt-<giờ>`, khôi phục từ bản sao gần nhất và ghi một dòng cảnh báo vào Nhật ký.
- **Upstash Redis** (khi đặt `UPSTASH_REDIS_REST_URL` + `UPSTASH_REDIS_REST_TOKEN` + `DATA_KEY`): dùng khi nơi chạy **không có ổ đĩa bền** (Render gói Free). Toàn bộ dữ liệu được nén và **mã hoá AES-256-GCM bằng `DATA_KEY`** trước khi gửi đi, nên Upstash không đọc được token hay cấu hình. Thay đổi được gộp và ghi sau ~1,5 giây, tự thử lại khi lỗi mạng (giao diện hiện cảnh báo đỏ khi chưa ghi được) và được ghi nốt khi tool bị tắt. Nếu lúc khởi động không đọc được dữ liệu (mạng lỗi, sai `DATA_KEY`) thì tool **dừng lại** thay vì chạy với dữ liệu trống rồi ghi đè.
  - **Lưu `DATA_KEY` ở nơi khác** (trình quản lý mật khẩu). Mất khoá thì không giải mã được dữ liệu trên Upstash (token nhập lại được, lịch/rule phải tạo lại). Đừng đổi `DATA_KEY` khi đã có dữ liệu.
  - Chỉ chạy **một nơi** dùng chung một Upstash: hai bản cùng chạy sẽ ghi đè nhau và làm việc hai lần trên cùng tài khoản Facebook.
  - Lần đầu, nếu chạy ở máy có sẵn `data.json` thì dữ liệu đó được đưa lên Upstash (dùng để chuyển từ máy bạn lên Render).
  - Đặt biến `UPSTASH_…` mà thiếu `DATA_KEY` (hoặc sai định dạng) thì tool từ chối khởi động và nói rõ thiếu gì.

## Deploy lên Render
### Cách A: gói Free + Upstash (không mất tiền)
Gói Free không có ổ đĩa, nên dữ liệu để ở Upstash. Gói Free còn **tự ngủ sau 15 phút không có truy cập**, nên cần một dịch vụ bên ngoài gọi vào định kỳ để giữ tool thức (bước 4), nếu không lịch buổi sáng có thể không chạy đúng giờ.
1. **Upstash**: đăng ký upstash.com → Create Database (Redis, chọn vùng gần Singapore) → tab **REST API**: sao chép `UPSTASH_REDIS_REST_URL` và `UPSTASH_REDIS_REST_TOKEN`.
2. **Tạo `DATA_KEY`**: chuỗi ngẫu nhiên dài, ví dụ chạy `node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"`. Lưu nó vào trình quản lý mật khẩu.
3. **Render**: dashboard.render.com → **New → Blueprint** → repo này, nhánh `dev`. Render đọc `render.yaml` (Docker, gói Free, Singapore). Khi được hỏi, nhập 4 biến: `APP_PASSWORD` (mật khẩu đăng nhập), `DATA_KEY`, `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`. Bấm Apply.
   - Nếu đã tạo service tay: **Environment** → thêm 4 biến trên (và `TZ` = `Asia/Ho_Chi_Minh`), **xoá `DATA_DIR`** nếu có, rồi Deploy lại. Gói Free thì không gắn Disk.
4. **Giữ tool thức**: dùng UptimeRobot (hoặc dịch vụ tương tự) tạo monitor HTTP(s) gọi `https://<tên>.onrender.com/api/auth` mỗi 5 phút. Bật cảnh báo qua Telegram/email để biết khi tool sập.
5. Xem log deploy, cần có `Lưu dữ liệu: Upstash (đã mã hoá bằng DATA_KEY)` và `Đăng nhập: BẬT`. Mở địa chỉ tool, đăng nhập rồi vào Cài đặt → Kết nối Facebook. Ở Cài đặt → Chung có dòng "Nơi lưu dữ liệu" cho biết lần lưu cuối.

Lưu ý gói Free: mỗi lần Render khởi động lại tool mất khoảng 1 phút, lịch trễ dưới 10 phút vẫn được chạy bù. Render có thể khởi động lại instance Free bất cứ lúc nào. Lịch phải chạy đúng giờ thì nên dùng cách B.

### Cách B: gói trả phí + ổ đĩa
Không cần Upstash: dữ liệu nằm ở `data.json` trên ổ đĩa (Disk) gắn vào `/data`. Trong `render.yaml`, bỏ đoạn "Cách A" và bỏ dấu `#` ở đoạn "Cách B" (gói trả phí + `disk` + `DATA_DIR=/data`), rồi Blueprint như trên (chỉ cần nhập `APP_PASSWORD`). Không có Disk thì `data.json` mất mỗi lần deploy.

### Chung cho cả hai cách
- Chỉ chạy **1 instance**. Đừng chạy song song bản ngrok/máy bạn với cùng token Facebook vì việc sẽ bị làm hai lần.
- Mỗi lần push lên `dev` Render tự deploy lại (đổi `autoDeployTrigger: off` trong `render.yaml` nếu không muốn).
- IP người dùng lấy từ tiêu đề `CF-Connecting-IP` (tự nhận biết qua biến `RENDER`); có thể ép bằng biến `CLIENT_IP_HEADER`.

## Kiểm tra dữ liệu (validate)
- Luật nghiệp vụ nằm ở một nơi: `shared/validate.mjs`, dùng chung cho server (kiểm tra cứng, không tin trình duyệt) và giao diện (báo lỗi ngay khi nhập).
- Chặn: lịch/rule thiếu giờ-ngày-camp, giảm ngân sách ≥ 100%, đổi ngân sách camp CBO, lịch bật/tắt xung đột, rule thiếu chi tiêu tối thiểu hoặc không có thời gian nghỉ, trần < sàn, khung giờ qua đêm, múi giờ/chu kỳ sai, token/Chat ID sai định dạng, mật khẩu yếu, chuyển Chạy thật khi chưa kết nối.
- Chạy kiểm thử: `npm test` (thư mục gốc, cần Node 20+): kiểm tra luật (`shared/validate.mjs`) và engine (khoảng thời gian, bảo vệ ngân sách, dừng khẩn, xem trước, hoàn tác) trên dữ liệu giả.

## An toàn
- Mặc định ở chế độ dùng thử (dữ liệu giả). Khi kết nối thật, mặc định "Chạy thử" (chỉ ghi log). Tắt sau vài ngày khi thấy log đúng ý.
- Server mặc định chỉ lắng nghe 127.0.0.1. Mở ra mạng (`HOST=0.0.0.0`) bắt buộc phải có `APP_PASSWORD`.
- Token lưu trong `data.json` trên máy bạn (đã nằm trong `.gitignore`) — không chia sẻ file này.
- Quên mật khẩu đặt trong Cài đặt: tắt tool, mở `data.json`, đặt `passwordHash` thành `""`, chạy lại.
- Nếu camp dùng CBO (ngân sách ở cấp camp), chỉnh ngân sách ở cấp camp; nhóm QC sẽ không có ngân sách riêng.
