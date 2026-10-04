// Gọi API hệ thống báo cáo nội bộ của công ty (mặc định https://mkt.companyos.site), giống hệt trang web của công ty:
//  - Đăng nhập: POST /api/auth/login { email, password } → cookie `wellday_session` + mã `csrf` trong body.
//  - Mọi request sau gửi kèm cookie và header X-CSRF-Token. Gặp 401 (phiên hết hạn) → đăng nhập lại 1 lần rồi thử lại.
//  - Team: GET /api/teams. Báo cáo: GET /api/reports?from&to&team_id, gửi: POST /api/reports (body xem shared/companyReport.mjs).
//    Cập nhật báo cáo đã có: cũng POST /api/reports, kèm revision (lần sửa hiện tại) + reason. Báo cáo đã khoá (locked) thì không sửa được.
// Phiên đăng nhập chỉ giữ trong bộ nhớ (khởi động lại tool thì đăng nhập lại). Mật khẩu không bao giờ ra log/giao diện.
const store = require('./store');

const net = { timeoutMs: 20e3 }; // đổi được trong test
const COOKIE = 'wellday_session';
let session = null; // { key, cookie, csrf, user }

const cfg = () => store.get().company || {};
// Đổi tài khoản / địa chỉ → bỏ phiên cũ
const keyOf = (c) => `${c.baseUrl}|${c.email}|${c.password}`;

function httpErr(message, status) { const e = new Error(message); e.status = status || 502; return e; }

async function raw(path, { method = 'GET', body, auth } = {}) {
  const c = cfg();
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
  if (auth) { headers.Cookie = `${COOKIE}=${auth.cookie}`; headers['X-CSRF-Token'] = auth.csrf; }
  let res;
  try {
    res = await fetch(`${c.baseUrl}/api${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual', signal: AbortSignal.timeout(net.timeoutMs) });
  } catch (e) {
    if (e && (e.name === 'TimeoutError' || e.name === 'AbortError')) throw httpErr(`Hệ thống công ty không trả lời sau ${Math.round(net.timeoutMs / 1000)} giây.`, 504);
    throw httpErr('Không kết nối được tới hệ thống công ty. Kiểm tra mạng hoặc địa chỉ hệ thống.');
  }
  let json = null;
  try { json = await res.json(); } catch { /* không phải JSON */ }
  return { res, json };
}

function cookieFrom(res) {
  const list = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || ''];
  for (const line of list) {
    const m = String(line).match(new RegExp(`(?:^|[;,]\\s*)${COOKIE}=([^;]+)`));
    if (m) return m[1];
  }
  return '';
}

async function login() {
  const c = cfg();
  if (!c.email || !c.password) throw httpErr('Chưa nhập email/mật khẩu hệ thống công ty (Cài đặt → Báo cáo công ty).', 400);
  const { res, json } = await raw('/auth/login', { method: 'POST', body: { email: c.email, password: c.password } });
  if (res.status === 401 || res.status === 400) throw httpErr(`Hệ thống công ty từ chối đăng nhập: ${(json && json.error) || 'sai email hoặc mật khẩu'}.`, 400);
  if (res.status === 429) throw httpErr('Hệ thống công ty đang chặn đăng nhập vì thử quá nhiều lần. Đợi vài phút rồi thử lại.', 429);
  if (!res.ok) throw httpErr(`Đăng nhập hệ thống công ty lỗi ${res.status}${json && json.error ? `: ${json.error}` : ''}.`);
  const cookie = cookieFrom(res);
  if (!cookie || !json || !json.csrf) throw httpErr('Đăng nhập hệ thống công ty không trả về phiên làm việc (có thể API đã đổi).');
  if (json.user && json.user.must_change) throw httpErr('Tài khoản công ty đang bắt đổi mật khẩu. Hãy đăng nhập trên web công ty, đổi mật khẩu rồi nhập mật khẩu mới vào tool.', 400);
  session = { key: keyOf(c), cookie, csrf: json.csrf, user: json.user || null };
  return session;
}

async function ensure() {
  if (!session || session.key !== keyOf(cfg())) await login();
  return session;
}

// Gọi API có đăng nhập; phiên hết hạn (401) → đăng nhập lại đúng 1 lần
async function call(path, opts = {}) {
  let s = await ensure();
  let { res, json } = await raw(path, { ...opts, auth: s });
  if (res.status === 401) {
    session = null;
    s = await login();
    ({ res, json } = await raw(path, { ...opts, auth: s }));
  }
  if (!res.ok) throw httpErr(`Hệ thống công ty báo lỗi${json && json.error ? `: ${json.error}` : ` ${res.status}`}`, res.status >= 500 ? 502 : 400);
  return json;
}

// Kiểm tra kết nối: đăng nhập lại từ đầu → { user: { name, email, role } }
async function test() {
  session = null;
  const s = await login();
  const u = s.user || {};
  return { user: { name: u.name || '', email: u.email || '', role: u.role || '' } };
}

async function listTeams() {
  const list = await call('/teams');
  return (Array.isArray(list) ? list : []).map((t) => ({ id: String(t.id), code: t.code || '', name: t.name || '', status: t.status || '' }));
}

// Báo cáo của chính tài khoản này cho một Team trong khoảng ngày (GET /api/reports?from&to&team_id)
async function listReports(teamId, from, to) {
  const s = await ensure();
  const q = new URLSearchParams({ from, to, team_id: teamId }).toString();
  const list = await call(`/reports?${q}`);
  const me = s.user && s.user.id;
  return (Array.isArray(list) ? list : []).filter((r) => String(r.team_id) === String(teamId) && (!me || !r.user_id || r.user_id === me))
    .map((r) => ({ ...r, date: String(r.date || '').slice(0, 10) }));
}

// Báo cáo của chính tài khoản này cho Team/ngày/mốc đã gửi chưa → báo cáo đó hoặc null
async function findReport(teamId, date, slot) {
  return (await listReports(teamId, date, date)).find((r) => r.date === date && Number(r.slot) === Number(slot)) || null;
}

const submitReport = (payload) => call('/reports', { method: 'POST', body: payload });

const reset = () => { session = null; };

module.exports = { _net: net, login, test, listTeams, listReports, findReport, submitReport, reset };
