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
- Telegram: thông báo mỗi thay đổi + báo cáo hằng ngày.
- Nhật ký chi tiết: bấm một dòng để xem nguyên nhân lỗi, cách khắc phục, mã lỗi Facebook, yêu cầu đã gửi, trước/sau, điều kiện rule; có nút sao chép và chạy lại. Token không bao giờ được ghi vào nhật ký.
- Giao diện sáng/tối/theo hệ thống, 5 màu nhấn, thanh lệnh nhanh `Ctrl K`, dùng được trên điện thoại.
- Hướng dẫn ngay trong app: trang **Hướng dẫn** (tìm kiếm, FAQ, thuật ngữ), dấu `?` giải thích cạnh các ô khó, thẻ **Bắt đầu nhanh** tự tick theo tiến độ.
- Đăng nhập bằng mật khẩu (Cài đặt → Bảo mật, hoặc biến môi trường `APP_PASSWORD`).

## Kiểm tra dữ liệu (validate)
- Luật nghiệp vụ nằm ở một nơi: `shared/validate.mjs`, dùng chung cho server (kiểm tra cứng, không tin trình duyệt) và giao diện (báo lỗi ngay khi nhập).
- Chặn: lịch/rule thiếu giờ-ngày-camp, giảm ngân sách ≥ 100%, đổi ngân sách camp CBO, lịch bật/tắt xung đột, rule thiếu chi tiêu tối thiểu hoặc không có thời gian nghỉ, trần < sàn, khung giờ qua đêm, múi giờ/chu kỳ sai, token/Chat ID sai định dạng, mật khẩu yếu, chuyển Chạy thật khi chưa kết nối.
- Chạy kiểm thử: `npm test` (thư mục gốc, cần Node 20+).

## An toàn
- Mặc định ở chế độ dùng thử (dữ liệu giả). Khi kết nối thật, mặc định "Chạy thử" (chỉ ghi log). Tắt sau vài ngày khi thấy log đúng ý.
- Server mặc định chỉ lắng nghe 127.0.0.1. Mở ra mạng (`HOST=0.0.0.0`) bắt buộc phải có `APP_PASSWORD`.
- Token lưu trong `data.json` trên máy bạn (đã nằm trong `.gitignore`) — không chia sẻ file này.
- Quên mật khẩu đặt trong Cài đặt: tắt tool, mở `data.json`, đặt `passwordHash` thành `""`, chạy lại.
- Nếu camp dùng CBO (ngân sách ở cấp camp), chỉnh ngân sách ở cấp camp; nhóm QC sẽ không có ngân sách riêng.
