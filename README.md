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
- Giao diện sáng/tối/theo hệ thống, 5 màu nhấn, thanh lệnh nhanh `Ctrl K`, dùng được trên điện thoại.
- Đăng nhập bằng mật khẩu (Cài đặt → Bảo mật, hoặc biến môi trường `APP_PASSWORD`).

## An toàn
- Mặc định ở chế độ dùng thử (dữ liệu giả). Khi kết nối thật, mặc định "Chạy thử" (chỉ ghi log). Tắt sau vài ngày khi thấy log đúng ý.
- Server mặc định chỉ lắng nghe 127.0.0.1. Mở ra mạng (`HOST=0.0.0.0`) bắt buộc phải có `APP_PASSWORD`.
- Token lưu trong `data.json` trên máy bạn (đã nằm trong `.gitignore`) — không chia sẻ file này.
- Quên mật khẩu đặt trong Cài đặt: tắt tool, mở `data.json`, đặt `passwordHash` thành `""`, chạy lại.
- Nếu camp dùng CBO (ngân sách ở cấp camp), chỉnh ngân sách ở cấp camp; nhóm QC sẽ không có ngân sách riêng.
