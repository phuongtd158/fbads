// Facebook Ads Auto Tool — server Express (chạy `npm install` một lần trước khi chạy).
const http = require('http');
const fs = require('fs');
const path = require('path');

// Biến môi trường đặt trong file .env cạnh server.js (vd UPSTASH_…, DATA_KEY, APP_PASSWORD) — tiện khi chạy trên máy bằng start.bat.
// Biến đã đặt sẵn trong môi trường (Render, cửa sổ lệnh) được ưu tiên hơn file. Cần Node 20.12+; bản cũ hơn thì bỏ qua file.
const ENV_FILE = path.join(__dirname, '.env');
if (fs.existsSync(ENV_FILE)) {
  if (typeof process.loadEnvFile === 'function') {
    try { process.loadEnvFile(ENV_FILE); } catch (e) { console.error(`\n  ❌ Không đọc được file .env: ${e.message}\n`); process.exit(1); }
  } else console.warn('  ⚠ Có file .env nhưng Node quá cũ để đọc (cần 20.12+). Hãy cập nhật Node hoặc đặt biến môi trường bằng tay.');
}

let express;
try { express = require('express'); } catch {
  console.error('\n  ❌ Chưa cài thư viện của server. Mở cửa sổ lệnh ở thư mục tool và chạy: npm install\n');
  process.exit(1);
}
const store = require('./lib/store');
const engine = require('./lib/engine');
const auth = require('./lib/auth');
const mw = require('./lib/middleware');

const PORT = process.env.PORT || 3000;
// Mặc định chỉ nghe trên máy này. Trên Render (nền tảng bắt buộc nghe 0.0.0.0) tự mở ra; nơi khác đặt HOST=0.0.0.0
// (bắt buộc phải có mật khẩu, nếu không server không khởi động).
const HOST = process.env.HOST || (process.env.RENDER ? '0.0.0.0' : '127.0.0.1');
const PUBLIC = path.join(__dirname, 'public');
const INDEX = path.join(PUBLIC, 'index.html');

// V = shared/validate.mjs, D = shared/dates.mjs (ES module, nạp bằng import() lúc khởi động rồi truyền vào các route)
function createApp(shared) {
  const app = express();
  app.disable('x-powered-by');
  app.set('etag', false);
  app.set('query parser', (qs) => Object.fromEntries(new URLSearchParams(qs))); // ?a=1 → { a: '1' }, luôn là chuỗi

  // ----- API: xem các nhóm route trong lib/routes/ -----
  app.use('/api', mw.apiHeaders, mw.sameOriginJson, mw.jsonBody, mw.requireAuth);
  for (const group of ['auth', 'settings', 'objects', 'automation', 'facebook']) app.use('/api', require(`./lib/routes/${group}`)(shared));
  app.use('/api', mw.notFound);

  // ----- File tĩnh của bản build Vue (public/). Đường dẫn lạ → index.html (SPA). -----
  app.use(express.static(PUBLIC, {
    redirect: false,
    cacheControl: false,
    setHeaders: (res, file) => res.set({
      'Cache-Control': file.includes(`${path.sep}assets${path.sep}`) ? 'public, max-age=31536000, immutable' : 'no-cache', // file có hash trong tên → cache lâu
      'X-Content-Type-Options': 'nosniff',
    }),
  }));
  app.use((req, res) => {
    if (!fs.existsSync(INDEX)) return res.status(503).type('text/plain; charset=utf-8').send('Chưa có bản build giao diện. Chạy: cd web && npm install && npm run build');
    res.set({ 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' }).sendFile(INDEX);
  });

  app.use(mw.errorHandler);
  return app;
}

const fatal = (msg) => { console.error(`\n  ❌ ${msg}\n`); process.exit(1); };

// Tắt (Render gửi SIGTERM khi deploy/khởi động lại): ghi nốt thay đổi đang chờ lên nơi lưu trữ rồi mới thoát
let closing = false;
async function shutdown(sig) {
  if (closing) return;
  closing = true;
  console.log(`\n  Nhận ${sig}, đang lưu dữ liệu trước khi tắt…`);
  try { await Promise.race([store.flush(), new Promise((r) => setTimeout(r, 8000))]); } catch { /* đã ghi log lỗi */ }
  process.exit(0);
}
for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => shutdown(sig));

(async () => {
  let shared;
  try { shared = { V: await import('./shared/validate.mjs'), D: await import('./shared/dates.mjs') }; } catch (e) { return fatal(`Không nạp được module dùng chung (shared/): ${e.message}`); }
  // Nạp dữ liệu TRƯỚC khi kiểm tra mật khẩu: mật khẩu đặt trong Cài đặt nằm trong dữ liệu (nhất là khi lưu ở Upstash)
  try { await store.init(); } catch (e) { return fatal(e.message); }
  // Mặc định chỉ lắng nghe trên máy bạn (127.0.0.1). Mở ra mạng mà không có mật khẩu thì từ chối chạy.
  if (!['127.0.0.1', 'localhost', '::1'].includes(HOST) && !auth.enabled()) {
    return fatal('HOST mở ra mạng nhưng chưa có mật khẩu.\n  Hãy đặt biến môi trường APP_PASSWORD (tối thiểu 8 ký tự) rồi chạy lại.');
  }
  const server = http.createServer(createApp(shared));
  server.on('error', (e) => {
    if (e.code === 'EADDRINUSE') {
      return fatal(`Cổng ${PORT} đang được dùng — rất có thể tool đã chạy sẵn ở một cửa sổ khác (bản đó vẫn dùng cấu hình cũ).\n  Hãy đóng cửa sổ đó (hoặc tắt tiến trình node đang chạy) rồi chạy lại. Muốn chạy song song thì đặt PORT khác.`);
    }
    fatal(`Không mở được cổng ${PORT}: ${e.message}`);
  });
  server.listen(PORT, HOST, () => {
    const st = store.status();
    console.log(`\n  Facebook Ads Auto Tool đang chạy: http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}\n  Đăng nhập: ${auth.enabled() ? 'BẬT' : 'tắt (chỉ dùng trên máy này)'}\n  Lưu dữ liệu: ${st.mode === 'remote' ? `${st.provider} (đã mã hoá bằng DATA_KEY)` : 'file data.json'}\n  Giữ cửa sổ này mở để lịch tự động hoạt động.\n`);
    engine.start();
  });
})();
