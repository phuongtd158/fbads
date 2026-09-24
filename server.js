// Facebook Ads Auto Tool — server không cần cài thư viện (Node 18+).
const http = require('http');
const fs = require('fs');
const path = require('path');
const store = require('./lib/store');
const fb = require('./lib/fb');
const engine = require('./lib/engine');
const notify = require('./lib/notify');
const auth = require('./lib/auth');

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '127.0.0.1'; // deploy: đặt HOST=0.0.0.0 (bắt buộc phải có mật khẩu)
const PUBLIC = path.join(__dirname, 'public');
const SECRETS = ['accessToken', 'telegramToken', 'passwordHash'];
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.map': 'application/json',
};

const send = (res, code, body) => {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(body));
};
const readBody = (req) => new Promise((resolve) => {
  let s = '';
  req.on('data', (c) => (s += c));
  req.on('end', () => { try { resolve(JSON.parse(s || '{}')); } catch { resolve({}); } });
});

function publicSettings() {
  const out = { ...store.get().settings };
  for (const k of SECRETS) { out[`has_${k}`] = !!out[k]; out[k] = ''; }
  return out;
}

function upsert(list, item) {
  if (!item.id) { item.id = store.uid(); list.push(item); }
  else { const i = list.findIndex((x) => x.id === item.id); if (i >= 0) list[i] = item; else list.push(item); }
  store.save();
  return item;
}

async function api(req, res, url) {
  const d = store.get();
  const m = req.method, p = url.pathname;
  let mt;

  // ----- Đăng nhập -----
  if (m === 'GET' && p === '/api/auth') return send(res, 200, { required: auth.enabled(), authed: auth.isAuthed(req), envManaged: auth.envManaged() });
  if (m === 'POST' && p === '/api/login') {
    const wait = auth.lockedMinutes(req);
    if (wait) return send(res, 429, { error: `Nhập sai quá nhiều lần. Thử lại sau ${wait} phút.` });
    const b = await readBody(req);
    if (!auth.enabled()) return send(res, 200, { ok: true });
    if (auth.verify(String(b.password || ''))) {
      auth.clearFails(req);
      auth.setCookie(req, res, auth.newSession(), 30 * 86400);
      return send(res, 200, { ok: true });
    }
    auth.recordFail(req);
    await new Promise((r) => setTimeout(r, 600)); // làm chậm dò mật khẩu
    return send(res, 401, { error: 'Sai mật khẩu' });
  }
  if (m === 'POST' && p === '/api/logout') { auth.endSession(req); auth.setCookie(req, res, '', 0); return send(res, 200, { ok: true }); }
  if (!auth.isAuthed(req)) return send(res, 401, { error: 'Cần đăng nhập' });

  if (m === 'POST' && p === '/api/password') {
    if (auth.envManaged()) return send(res, 400, { error: 'Mật khẩu đang được đặt bằng biến môi trường APP_PASSWORD trên server, không đổi ở đây được.' });
    const b = await readBody(req), next = String(b.newPassword || '');
    if (next.length < 8) return send(res, 400, { error: 'Mật khẩu mới cần ít nhất 8 ký tự.' });
    if (auth.enabled() && !auth.verify(String(b.currentPassword || ''))) return send(res, 400, { error: 'Mật khẩu hiện tại không đúng.' });
    auth.setPassword(next);
    auth.setCookie(req, res, auth.newSession(true), 30 * 86400); // đăng xuất mọi thiết bị khác
    return send(res, 200, { ok: true });
  }

  if (m === 'GET' && p === '/api/state') return send(res, 200, { settings: publicSettings(), schedules: d.schedules, rules: d.rules });
  if (m === 'GET' && p === '/api/objects') return send(res, 200, await fb.listObjects(url.searchParams.get('refresh') === '1'));
  if (m === 'GET' && p === '/api/logs') return send(res, 200, d.logs.slice(0, 300));

  if ((mt = p.match(/^\/api\/objects\/([^/]+)\/status$/)) && m === 'POST') {
    const b = await readBody(req);
    await fb.setStatus(mt[1], !!b.on);
    store.log({ source: 'Thủ công', name: b.name || mt[1], detail: b.on ? 'Bật camp' : 'Tắt camp', ok: true });
    return send(res, 200, { ok: true });
  }
  if ((mt = p.match(/^\/api\/objects\/([^/]+)\/budget$/)) && m === 'POST') {
    const b = await readBody(req);
    if (!(b.amount > 0)) return send(res, 400, { error: 'Ngân sách không hợp lệ' });
    await fb.setBudget(mt[1], b.amount);
    store.log({ source: 'Thủ công', name: b.name || mt[1], detail: `Đặt ngân sách ${Math.round(b.amount).toLocaleString('vi-VN')}`, ok: true });
    return send(res, 200, { ok: true });
  }

  if (m === 'POST' && p === '/api/settings') {
    const b = await readBody(req);
    for (const k of Object.keys(d.settings)) {
      if (!(k in b) || k === 'passwordHash') continue;
      if (SECRETS.includes(k) && b[k] === '') continue; // để trống = giữ nguyên
      d.settings[k] = typeof d.settings[k] === 'number' ? Number(b[k]) : b[k];
    }
    store.save();
    await fb.listObjects(true).catch(() => {});
    return send(res, 200, publicSettings());
  }

  for (const [name, list] of [['schedules', d.schedules], ['rules', d.rules]]) {
    if (m === 'POST' && p === `/api/${name}`) return send(res, 200, upsert(list, await readBody(req)));
    if ((mt = p.match(new RegExp(`^/api/${name}/([^/]+)$`))) && m === 'DELETE') {
      const i = list.findIndex((x) => x.id === mt[1]);
      if (i >= 0) list.splice(i, 1);
      store.save();
      return send(res, 200, { ok: true });
    }
  }
  if ((mt = p.match(/^\/api\/schedules\/([^/]+)\/run$/)) && m === 'POST') {
    const s = d.schedules.find((x) => x.id === mt[1]);
    if (!s) return send(res, 404, { error: 'Không tìm thấy lịch' });
    await engine.runSchedule(s);
    return send(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/rules/run') { await engine.runRules(); return send(res, 200, { ok: true }); }
  if ((m === 'POST' || m === 'GET') && (p === '/api/test-connection' || p === '/api/connection')) {
    try { return send(res, 200, await fb.testConnection()); } catch (e) { return send(res, 200, { ok: false, error: e.message }); }
  }
  // Liệt kê tài khoản quảng cáo từ token (token mới dán chưa cần lưu).
  if (m === 'POST' && p === '/api/fb/accounts') {
    const b = await readBody(req);
    const token = (b.token || '').trim() || d.settings.accessToken;
    if (!token) return send(res, 400, { error: 'Hãy dán Access Token trước.' });
    return send(res, 200, await fb.listAccounts(token));
  }
  // Đổi token ngắn hạn thành ~60 ngày. App ID/Secret chỉ dùng 1 lần, không lưu.
  if (m === 'POST' && p === '/api/fb/extend') {
    const b = await readBody(req);
    const token = (b.token || '').trim() || d.settings.accessToken;
    if (!token || !b.appId || !b.appSecret) return send(res, 400, { error: 'Cần Access Token, App ID và App Secret.' });
    const long = await fb.extendToken(String(b.appId).trim(), String(b.appSecret).trim(), token);
    d.settings.accessToken = long; store.save();
    return send(res, 200, { ok: true, token: await fb.inspectToken(long) });
  }
  if (m === 'POST' && p === '/api/telegram/test') {
    const ok = await notify.telegram('✅ Kết nối Telegram thành công — Facebook Ads Auto Tool');
    return send(res, ok ? 200 : 400, ok ? { ok } : { error: 'Gửi thất bại, kiểm tra Bot Token và Chat ID' });
  }
  if (m === 'POST' && p === '/api/report') { await engine.sendReport(); return send(res, 200, { ok: true }); }
  send(res, 404, { error: 'Not found' });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname.startsWith('/api/')) {
      // Chặn gọi chéo từ trang web khác (CSRF): request ghi phải cùng origin và là JSON.
      if (req.method !== 'GET') {
        const o = req.headers.origin;
        if (o && new URL(o).host !== req.headers.host) return send(res, 403, { error: 'Origin không hợp lệ' });
        if (!String(req.headers['content-type'] || '').includes('application/json')) return send(res, 415, { error: 'Cần Content-Type: application/json' });
      }
      return await api(req, res, url);
    }
    // File tĩnh của bản build Vue (public/). Đường dẫn lạ → index.html (SPA).
    let file = path.join(PUBLIC, decodeURIComponent(url.pathname));
    if (!file.startsWith(PUBLIC)) { res.writeHead(403); return res.end('Forbidden'); }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(PUBLIC, 'index.html');
    if (!fs.existsSync(file)) { res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('Chưa có bản build giao diện. Chạy: cd web && npm install && npm run build'); }
    const ext = path.extname(file).toLowerCase();
    const hashed = file.includes(`${path.sep}assets${path.sep}`); // file có hash trong tên → cache lâu
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Cache-Control': hashed ? 'public, max-age=31536000, immutable' : 'no-cache',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(fs.readFileSync(file));
  } catch (e) {
    send(res, 500, { error: e.message });
  }
});

// Mặc định chỉ lắng nghe trên máy bạn (127.0.0.1). Mở ra mạng mà không có mật khẩu thì từ chối chạy.
if (!['127.0.0.1', 'localhost', '::1'].includes(HOST) && !auth.enabled()) {
  console.error('\n  ❌ HOST mở ra mạng nhưng chưa có mật khẩu.\n  Hãy đặt biến môi trường APP_PASSWORD (tối thiểu 8 ký tự) rồi chạy lại.\n');
  process.exit(1);
}
server.listen(PORT, HOST, () => {
  console.log(`\n  Facebook Ads Auto Tool đang chạy: http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}\n  Đăng nhập: ${auth.enabled() ? 'BẬT' : 'tắt (chỉ dùng trên máy này)'}\n  Giữ cửa sổ này mở để lịch tự động hoạt động.\n`);
  engine.start();
});
