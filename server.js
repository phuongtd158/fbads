// Facebook Ads Auto Tool — server không cần cài thư viện (Node 18+).
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// Biến môi trường đặt trong file .env cạnh server.js (vd UPSTASH_…, DATA_KEY, APP_PASSWORD) — tiện khi chạy trên máy bằng start.bat.
// Biến đã đặt sẵn trong môi trường (Render, Fly, cửa sổ lệnh) được ưu tiên hơn file. Cần Node 20.12+; bản cũ hơn thì bỏ qua file.
const ENV_FILE = path.join(__dirname, '.env');
if (fs.existsSync(ENV_FILE)) {
  if (typeof process.loadEnvFile === 'function') {
    try { process.loadEnvFile(ENV_FILE); } catch (e) { console.error(`\n  ❌ Không đọc được file .env: ${e.message}\n`); process.exit(1); }
  } else console.warn('  ⚠ Có file .env nhưng Node quá cũ để đọc (cần 20.12+). Hãy cập nhật Node hoặc đặt biến môi trường bằng tay.');
}

const store = require('./lib/store');
const fb = require('./lib/fb');
const engine = require('./lib/engine');
const notify = require('./lib/notify');
const auth = require('./lib/auth');

let V = null; // shared/validate.mjs (ES module, nạp bằng import() lúc khởi động)
let D = null; // shared/dates.mjs: khoảng ngày cho số liệu ở Tổng quan
const bad = (res, r) => send(res, 400, { error: r.first || r.error, errors: r.errors || {} });

const PORT = process.env.PORT || 3000;
// Mặc định chỉ nghe trên máy này. Trên Render/Fly (nền tảng bắt buộc nghe 0.0.0.0) tự mở ra; nơi khác đặt HOST=0.0.0.0
// (bắt buộc phải có mật khẩu, nếu không server không khởi động).
const HOST = process.env.HOST || (process.env.RENDER || process.env.FLY_APP_NAME ? '0.0.0.0' : '127.0.0.1');
const PUBLIC = path.join(__dirname, 'public');
const SECRETS = ['accessToken', 'telegramToken', 'passwordHash', 'fbAppSecret'];
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
  req.on('data', (c) => { s += c; if (s.length > 1e6) { s = ''; req.destroy(); } }); // chặn body quá lớn (1MB)
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

// ----- Đăng nhập bằng Facebook (OAuth) -----
// Cookie phiên là SameSite=Strict nên khi Facebook chuyển về /api/fb/callback trình duyệt KHÔNG gửi cookie.
// Callback vì vậy không đòi đăng nhập, mà dựa vào `state`: mã ngẫu nhiên dùng 1 lần, chỉ người đã đăng nhập mới tạo được, sống 10 phút.
const oauthStates = new Map();
let oauthError = '';
const OAUTH_BACK = '/#/settings/connection?fbLogin=';
function redirectUri(req) {
  if (process.env.PUBLIC_URL) return `${process.env.PUBLIC_URL.replace(/\/+$/, '')}/api/fb/callback`;
  const proto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() || (req.socket.encrypted ? 'https' : 'http');
  return `${proto}://${req.headers.host}/api/fb/callback`;
}
function newOauthState(appId, uri) {
  const now = Date.now();
  for (const [k, v] of oauthStates) if (v.exp < now) oauthStates.delete(k);
  if (oauthStates.size > 50) oauthStates.clear();
  const st = crypto.randomBytes(24).toString('hex');
  oauthStates.set(st, { appId, redirectUri: uri, exp: now + 10 * 60000 });
  return st;
}
async function oauthCallback(res, url) {
  const q = url.searchParams, st = q.get('state') || '';
  const pending = oauthStates.get(st);
  oauthStates.delete(st);
  // Chỉ đưa mã kết quả lên URL; nội dung lỗi lấy qua /api/fb/oauth (tránh người ngoài chèn chữ tuỳ ý vào giao diện)
  const back = (r) => { res.writeHead(302, { Location: OAUTH_BACK + r, 'Cache-Control': 'no-store' }); res.end(); };
  if (!pending || pending.exp < Date.now()) return back('expired');
  if (q.get('error') || !q.get('code')) return back('cancel'); // người dùng bấm Huỷ trên Facebook
  const s = store.get().settings;
  try {
    s.accessToken = await fb.exchangeCode({ appId: pending.appId, appSecret: s.fbAppSecret, redirectUri: pending.redirectUri, code: q.get('code') });
    store.save();
    fb.resetCache();
    oauthError = '';
    return back('ok');
  } catch (e) {
    oauthError = e.message;
    return back('fail');
  }
}

// Ghi nhật ký cho thao tác thủ công (kể cả khi lỗi) rồi ném lại lỗi cho giao diện
async function manualAction(id, name, action, okDetail, after, fn) {
  const s = store.get().settings;
  const cur = (fb.peekObjects() || []).find((o) => o.id === id);
  const base = { kind: 'manual', source: 'Thủ công', name: name || id, target: { id, name: name || id, level: cur && cur.level, ...(cur && cur.accountId ? { accountId: cur.accountId, accountName: cur.accountName } : {}) }, action, before: fb.snapshot(cur), mode: s.mock ? 'mock' : s.dryRun ? 'dry' : 'live' };
  try {
    await fn();
    store.log({ ...base, detail: okDetail, ok: true, after });
  } catch (e) {
    store.log({ ...base, detail: e.message, ok: false, error: fb.describeError(e) });
    throw e;
  }
}

async function api(req, res, url) {
  const d = store.get();
  const m = req.method, p = url.pathname;
  let mt;

  // ----- Đăng nhập -----
  if ((m === 'GET' || m === 'HEAD') && p === '/api/auth') return send(res, 200, { required: auth.enabled(), authed: auth.isAuthed(req), envManaged: auth.envManaged() });
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
  if (m === 'GET' && p === '/api/fb/callback') return oauthCallback(res, url);
  if (m === 'POST' && p === '/api/logout') { auth.endSession(req); auth.setCookie(req, res, '', 0); return send(res, 200, { ok: true }); }
  if (!auth.isAuthed(req)) return send(res, 401, { error: 'Cần đăng nhập' });

  if (m === 'POST' && p === '/api/password') {
    if (auth.envManaged()) return send(res, 400, { error: 'Mật khẩu đang được đặt bằng biến môi trường APP_PASSWORD trên server, không đổi ở đây được.' });
    const b = await readBody(req), next = String(b.newPassword || '');
    const pv = V.validatePassword(next, String(b.currentPassword || ''));
    if (!pv.ok) return bad(res, pv);
    if (auth.enabled() && !auth.verify(String(b.currentPassword || ''))) return send(res, 400, { error: 'Mật khẩu hiện tại không đúng.' });
    auth.setPassword(next);
    auth.setCookie(req, res, auth.newSession(true), 30 * 86400); // đăng xuất mọi thiết bị khác
    return send(res, 200, { ok: true });
  }

  if (m === 'GET' && p === '/api/state') return send(res, 200, { settings: publicSettings(), schedules: d.schedules, rules: d.rules, storage: store.status() });
  if (m === 'GET' && p === '/api/storage') return send(res, 200, store.status());
  if (m === 'GET' && p === '/api/objects') return send(res, 200, { items: await fb.listObjects(url.searchParams.get('refresh') === '1'), ...fb.objectsMeta() });
  // Số liệu theo khoảng ngày cho Tổng quan: ?range=last_7d hoặc ?since=2026-09-01&until=2026-09-20 (trống = hôm nay).
  // Không đụng tới danh sách camp/engine: chỉ trả { [id]: metrics }, giao diện ghép vào bảng.
  if (m === 'GET' && p === '/api/insights') {
    const today = D.todayIn(d.settings.timezone);
    const parsed = D.parseRange(Object.fromEntries(url.searchParams), today);
    if (!parsed.ok) return send(res, 400, { error: parsed.error });
    const r = D.resolveRange(parsed.spec, today);
    const q = { key: parsed.key, fbParams: D.fbParams(parsed.spec, today), days: r.days };
    const got = await fb.rangeData(q, url.searchParams.get('refresh') === '1');
    const meta = fb.objectsMeta();
    return send(res, 200, { range: parsed.spec, key: parsed.key, since: r.since, until: r.until, days: r.days, at: got.at || null, stale: got.stale,
      blockedUntil: meta.blockedUntil, usage: meta.usage, accountErrors: meta.accountErrors, metrics: got.data });
  }
  if (m === 'GET' && p === '/api/logs') return send(res, 200, d.logs.slice(0, 300));

  if ((mt = p.match(/^\/api\/objects\/([^/]+)\/status$/)) && m === 'POST') {
    const b = await readBody(req);
    await manualAction(mt[1], b.name, { type: b.on ? 'on' : 'off' }, b.on ? 'Bật camp' : 'Tắt camp', { status: b.on ? 'ACTIVE' : 'PAUSED' }, () => fb.setStatus(mt[1], !!b.on));
    return send(res, 200, { ok: true });
  }
  if ((mt = p.match(/^\/api\/objects\/([^/]+)\/budget$/)) && m === 'POST') {
    const b = await readBody(req);
    const bv = V.validateBudget(b.amount);
    if (!bv.ok) return bad(res, bv);
    const cur = (fb.peekObjects() || []).find((o) => o.id === mt[1]);
    if (cur && cur.dailyBudget == null) return send(res, 400, { error: 'Mục này không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO). Hãy chỉnh ở cấp có ngân sách.' });
    await manualAction(mt[1], b.name, { type: 'budget', mode: 'set', value: bv.value }, `Đặt ngân sách ${bv.value.toLocaleString('vi-VN')}`, { dailyBudget: bv.value }, () => fb.setBudget(mt[1], bv.value));
    return send(res, 200, { ok: true });
  }

  if (m === 'POST' && p === '/api/settings') {
    const b = await readBody(req);
    const sv = V.validateSettings(b, d.settings);
    if (!sv.ok) return bad(res, sv);
    // chỉ ghi các khóa đã được kiểm tra (sv.value) — không bao giờ ghi trực tiếp từ dữ liệu client
    for (const [k, val] of Object.entries(sv.value)) if (k in d.settings && k !== 'passwordHash') d.settings[k] = val;
    store.save();
    if ('mock' in sv.value || 'adAccountId' in sv.value || 'adAccountIds' in sv.value || 'accessToken' in sv.value) fb.resetCache(); // đổi nguồn dữ liệu → bỏ cache cũ
    await fb.listObjects(true).catch(() => {});
    return send(res, 200, publicSettings());
  }

  for (const [name, list] of [['schedules', d.schedules], ['rules', d.rules]]) {
    if (m === 'POST' && p === `/api/${name}`) {
      const b = await readBody(req);
      const ctx = { objs: fb.peekObjects(), [name]: list, accountTargets: d.settings.accountTargets || {}, accounts: fb.objectsMeta().accounts };
      const r = name === 'schedules' ? V.validateSchedule(b, ctx) : V.validateRule(b, ctx);
      if (!r.ok) return bad(res, r);
      if (!r.value.id && list.length >= 200) return send(res, 400, { error: 'Đã đạt giới hạn 200 mục. Hãy xoá bớt trước khi thêm mới.' });
      return send(res, 200, { ...upsert(list, r.value), warnings: r.warnings });
    }
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
    if (await engine.runSchedule(s) === 'blocked') return send(res, 429, { error: 'Facebook đang giới hạn số lần gọi nên lịch chưa chạy. Thử lại sau vài phút.', rateLimited: true });
    return send(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/rules/run') { await engine.runRules(); return send(res, 200, { ok: true }); }
  // Xem trước: rule (chưa lưu) đang khớp camp nào ngay bây giờ — không thay đổi gì
  if (m === 'POST' && p === '/api/rules/preview') {
    const b = await readBody(req);
    const r = V.validateRule({ ...b, enabled: true }, { objs: fb.peekObjects(), rules: [], accountTargets: d.settings.accountTargets || {}, accounts: fb.objectsMeta().accounts });
    if (!r.ok) return bad(res, r);
    return send(res, 200, { ...(await engine.previewRule(r.value)), warnings: r.warnings });
  }
  // Hoàn tác một thay đổi đã ghi trong nhật ký
  if ((mt = p.match(/^\/api\/logs\/([^/]+)\/undo$/)) && m === 'POST') {
    const b = await readBody(req);
    return send(res, 200, { ok: true, entry: await engine.undoLog(mt[1], { force: !!b.force }) });
  }
  if ((m === 'POST' || m === 'GET') && (p === '/api/test-connection' || p === '/api/connection')) {
    try { return send(res, 200, await fb.testConnection()); } catch (e) { return send(res, 200, { ok: false, error: e.message }); }
  }
  // Liệt kê tài khoản quảng cáo từ token (token mới dán chưa cần lưu).
  if (m === 'POST' && p === '/api/fb/accounts') {
    const b = await readBody(req);
    const token = (b.token || '').trim() || d.settings.accessToken;
    const tm = V.checkToken(token);
    if (tm) return send(res, 400, { error: tm });
    return send(res, 200, await fb.listAccounts(token));
  }
  // Đổi token ngắn hạn thành ~60 ngày. App ID/Secret chỉ dùng 1 lần, không lưu.
  if (m === 'POST' && p === '/api/fb/extend') {
    const b = await readBody(req);
    const token = (b.token || '').trim() || d.settings.accessToken;
    const em = V.checkToken(token) || V.checkAppId(b.appId) || V.checkAppSecret(b.appSecret);
    if (em) return send(res, 400, { error: em });
    const long = await fb.extendToken(String(b.appId).trim(), String(b.appSecret).trim(), token);
    d.settings.accessToken = long; store.save();
    return send(res, 200, { ok: true, token: await fb.inspectToken(long) });
  }
  // Địa chỉ cần khai báo trong ứng dụng Meta + lỗi của lần đăng nhập Facebook gần nhất
  if (m === 'GET' && p === '/api/fb/oauth') return send(res, 200, { redirectUri: redirectUri(req), error: oauthError });
  // Bắt đầu đăng nhập bằng Facebook: lưu App ID/Secret (để lần sau chỉ cần bấm 1 nút) rồi trả về địa chỉ trang đăng nhập
  if (m === 'POST' && p === '/api/fb/oauth/start') {
    const b = await readBody(req), s = d.settings;
    const appId = String(b.appId || '').trim() || s.fbAppId;
    const appSecret = String(b.appSecret || '').trim() || (appId === s.fbAppId ? s.fbAppSecret : ''); // đổi ứng dụng thì phải nhập secret mới
    const configId = 'configId' in b ? String(b.configId || '').trim() : s.fbConfigId;
    const em = V.checkAppId(appId) || (appSecret ? V.checkAppSecret(appSecret) : 'Hãy nhập App Secret') || V.checkConfigId(configId);
    if (em) return send(res, 400, { error: em });
    Object.assign(s, { fbAppId: appId, fbAppSecret: appSecret, fbConfigId: configId });
    store.save();
    oauthError = '';
    const uri = redirectUri(req);
    return send(res, 200, { url: fb.oauthUrl({ appId, configId, redirectUri: uri, state: newOauthState(appId, uri) }) });
  }
  if (m === 'POST' && p === '/api/telegram/test') {
    const r = notify.reply(await notify.send('✅ Kết nối Telegram thành công — Facebook Ads Auto Tool'));
    return send(res, r.status, r.body);
  }
  if (m === 'POST' && p === '/api/report') { const r = notify.reply(await engine.sendReport()); return send(res, r.status, r.body); }
  send(res, 404, { error: 'Not found' });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname.startsWith('/api/')) {
      // Chặn gọi chéo từ trang web khác (CSRF): request ghi phải cùng origin và là JSON.
      // HEAD chỉ được phép trên /api/auth: các dịch vụ theo dõi (UptimeRobot…) hay dùng HEAD để kiểm tra tool còn sống
      if (req.method !== 'GET' && !(req.method === 'HEAD' && url.pathname === '/api/auth')) {
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
    send(res, e.status || 500, { error: e.message, ...(e.drift ? { drift: true } : {}), ...(fb.isRateLimited(e) ? { rateLimited: true } : {}) });
  }
});

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
  try { V = await import('./shared/validate.mjs'); D = await import('./shared/dates.mjs'); } catch (e) { return fatal(`Không nạp được module dùng chung (shared/): ${e.message}`); }
  // Nạp dữ liệu TRƯỚC khi kiểm tra mật khẩu: mật khẩu đặt trong Cài đặt nằm trong dữ liệu (nhất là khi lưu ở Upstash)
  try { await store.init(); } catch (e) { return fatal(e.message); }
  // Mặc định chỉ lắng nghe trên máy bạn (127.0.0.1). Mở ra mạng mà không có mật khẩu thì từ chối chạy.
  if (!['127.0.0.1', 'localhost', '::1'].includes(HOST) && !auth.enabled()) {
    return fatal('HOST mở ra mạng nhưng chưa có mật khẩu.\n  Hãy đặt biến môi trường APP_PASSWORD (tối thiểu 8 ký tự) rồi chạy lại.');
  }
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
