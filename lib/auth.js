// Đăng nhập theo tài khoản: mỗi tài khoản có tên + mật khẩu riêng và bộ dữ liệu riêng (lib/store.js, lib/ctx.js).
// Không có vai trò: ai đăng nhập cũng toàn quyền với dữ liệu của chính mình. Tài khoản admin (id 1, giữ dữ liệu của bản
// 1 người dùng) còn được tạo, đặt lại mật khẩu và xoá tài khoản khác.
// Mật khẩu admin lấy từ biến môi trường APP_PASSWORD (ưu tiên) hoặc đặt trong Cài đặt; mật khẩu lưu dạng băm scrypt.
// Chưa có tài khoản nào và không đặt APP_PASSWORD → không cần đăng nhập (chỉ dùng trên máy này), mọi thứ thuộc admin.
// Phiên lưu bằng cookie HttpOnly, giới hạn thử sai theo IP.
const crypto = require('crypto');
const store = require('./store');
const { ADMIN } = require('./ctx');

const SESSION_MS = 30 * 864e5;
const USERNAME = /^[a-z0-9._@+-]{3,64}$/;
const sha = (s) => crypto.createHash('sha256').update(s).digest();
const eq = (a, b) => a.length === b.length && crypto.timingSafeEqual(a, b);
const envPw = () => process.env.APP_PASSWORD || '';

const A = () => store.accounts();
const users = () => A().users;
const byId = (id) => users().find((u) => u.id === id) || null;
const normName = (s) => String(s || '').trim().toLowerCase();
const byName = (name) => users().find((u) => u.username === normName(name)) || null;
const newAdmin = (hash = '') => ({ id: ADMIN, username: 'admin', name: '', hash, createdAt: new Date().toISOString() });

// Bản cũ: mật khẩu nằm trong settings.passwordHash, phiên trong state.sessions của data.json → chuyển thành tài khoản admin
// (giữ nguyên các phiên đang đăng nhập). Không xoá dữ liệu cũ để quay lại bản cũ vẫn dùng được. Chạy 1 lần lúc khởi động.
function migrateLegacy() {
  if (byId(ADMIN)) return;
  const d = store.run(ADMIN, () => store.get());
  const hash = d.settings.passwordHash || '';
  if (!hash && !envPw()) return;
  users().push(newAdmin(hash));
  const S = A().sessions;
  for (const [k, exp] of Object.entries(d.state.sessions || {})) if (typeof exp === 'number' && !S[k]) S[k] = { uid: ADMIN, exp };
  store.saveAccounts();
}

const enabled = () => !!(envPw() || users().length);

function hashPw(pw) {
  const salt = crypto.randomBytes(16);
  return `${salt.toString('hex')}:${crypto.scryptSync(pw, salt, 64).toString('hex')}`;
}
function checkHash(pw, stored) {
  const [s, h] = (stored || '').split(':');
  if (!s || !h) return false;
  return eq(crypto.scryptSync(pw, Buffer.from(s, 'hex'), 64), Buffer.from(h, 'hex'));
}
// Có APP_PASSWORD mà chưa có tài khoản admin → tạo (mật khẩu vẫn lấy từ biến môi trường)
function ensureAdmin() {
  let u = byId(ADMIN);
  if (!u) { u = newAdmin(); users().push(u); store.saveAccounts(); }
  return u;
}
// → tài khoản nếu đúng tên + mật khẩu, không thì null. Bỏ trống tên = admin (giống trang đăng nhập cũ chỉ có ô mật khẩu).
function verify(username, pw) {
  const name = normName(username) || 'admin';
  const u = byName(name);
  if (envPw() && (u ? u.id === ADMIN : name === 'admin')) return eq(sha(pw), sha(envPw())) ? ensureAdmin() : null;
  return u && checkHash(pw, u.hash) ? u : null;
}

function setPassword(id, pw) {
  const u = id === ADMIN ? ensureAdmin() : byId(id);
  if (!u) throw new Error('Không tìm thấy tài khoản');
  u.hash = hashPw(pw);
  store.saveAccounts();
}

// Lỗi theo từng trường cho giao diện: { error, errors: { username: '…' } } với mã 400
function fieldError(field, msg) { const e = new Error(msg); e.status = 400; e.errors = { [field]: msg }; return e; }

function createUser(username, name, pw) {
  const un = normName(username);
  if (!USERNAME.test(un)) throw fieldError('username', 'Tên đăng nhập 3–64 ký tự: chữ thường không dấu, số và . _ @ + -');
  if (byName(un)) throw fieldError('username', 'Tên đăng nhập này đã có người dùng');
  if (!enabled()) throw Object.assign(new Error('Hãy đặt mật khẩu cho admin trước (Cài đặt → Bảo mật).'), { status: 400 });
  ensureAdmin();
  const a = A();
  const u = { id: a.nextId++, username: un, name: String(name || '').trim().slice(0, 100), hash: hashPw(pw), createdAt: new Date().toISOString() };
  a.users.push(u);
  store.saveAccounts();
  store.addUser(u.id);
  return u;
}

function deleteUser(id) {
  if (id === ADMIN) throw Object.assign(new Error('Không xoá được tài khoản admin'), { status: 400 });
  const a = A(), i = a.users.findIndex((u) => u.id === id);
  if (i < 0) throw Object.assign(new Error('Không tìm thấy tài khoản'), { status: 404 });
  a.users.splice(i, 1);
  for (const [k, v] of Object.entries(a.sessions)) if (v.uid === id) delete a.sessions[k];
  store.saveAccounts();
  store.removeUser(id);
}

const publicUser = (u) => u && { id: u.id, username: u.username, name: u.name || '', admin: u.id === ADMIN, createdAt: u.createdAt };
const list = () => users().map(publicUser);

// ----- Phiên đăng nhập (chỉ lưu bản băm của token) -----
function cookie(req, name) {
  for (const part of (req.headers.cookie || '').split(';')) {
    const i = part.indexOf('=');
    if (part.slice(0, i).trim() === name) { try { return decodeURIComponent(part.slice(i + 1).trim()); } catch { return ''; } }
  }
  return '';
}
// reset = đăng xuất mọi thiết bị khác của tài khoản này (khi đổi mật khẩu)
function newSession(uid, reset) {
  const S = A().sessions, now = Date.now();
  for (const k of Object.keys(S)) if (S[k].exp < now || (reset && S[k].uid === uid)) delete S[k];
  const t = crypto.randomBytes(32).toString('hex');
  S[sha(t).toString('hex')] = { uid, exp: now + SESSION_MS };
  store.saveAccounts();
  return t;
}
// Tài khoản của request: id, hoặc null nếu chưa đăng nhập. Chưa bật đăng nhập → admin.
function userIdOf(req) {
  if (!enabled()) return ADMIN;
  const t = cookie(req, 'sid');
  const s = t && A().sessions[sha(t).toString('hex')];
  if (!s || s.exp <= Date.now() || !byId(s.uid)) return null;
  return s.uid;
}
const isAuthed = (req) => userIdOf(req) != null;
function endSessionsOf(uid) {
  const S = A().sessions;
  for (const k of Object.keys(S)) if (S[k].uid === uid) delete S[k];
  store.saveAccounts();
}
function endSession(req) { const t = cookie(req, 'sid'); if (t) { delete A().sessions[sha(t).toString('hex')]; store.saveAccounts(); } }
function setCookie(req, res, val, maxAgeSec) {
  const secure = req.headers['x-forwarded-proto'] === 'https' || req.socket.encrypted;
  res.setHeader('Set-Cookie', `sid=${val}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAgeSec}${secure ? '; Secure' : ''}`);
}

// ----- Giới hạn thử sai: 5 lần sai → khoá 15 phút -----
const fails = new Map();
// Tiêu đề chứa IP thật của người dùng do proxy phía trước ĐẶT (người dùng không giả được qua proxy đó).
// Chỉ tin đúng tiêu đề của nền tảng đang chạy, vì nơi khác (ngrok…) người dùng có thể tự gửi tiêu đề này để lách giới hạn.
function clientIpHeader() {
  const h = (process.env.CLIENT_IP_HEADER || '').trim().toLowerCase();
  if (h) return h;
  if (process.env.RENDER) return 'cf-connecting-ip'; // Render (đứng sau Cloudflare)
  return '';
}
// Sau proxy: dùng tiêu đề trên; nếu không có thì lấy phần tử CUỐI của X-Forwarded-For (do proxy gần nhất thêm vào; phần tử
// đầu là chỗ người dùng có thể điền giả).
function ipOf(req) {
  if (process.env.TRUST_PROXY || process.env.RENDER) {
    const h = clientIpHeader(), v = h && req.headers[h];
    if (v) return String(v).split(',')[0].trim();
    const xf = (req.headers['x-forwarded-for'] || '').split(',').map((s) => s.trim()).filter(Boolean);
    if (xf.length) return xf[xf.length - 1];
  }
  return req.socket.remoteAddress;
}
function lockedMinutes(req) { const f = fails.get(ipOf(req)); return f && f.until > Date.now() ? Math.ceil((f.until - Date.now()) / 60000) : 0; }
function recordFail(req) {
  const k = ipOf(req), f = fails.get(k) || { n: 0, until: 0 };
  if (++f.n >= 5) { f.until = Date.now() + 15 * 60000; f.n = 0; }
  fails.set(k, f);
}
const clearFails = (req) => fails.delete(ipOf(req));

module.exports = {
  enabled, verify, setPassword, createUser, deleteUser, list, publicUser, byId, migrateLegacy, ensureAdmin,
  newSession, userIdOf, isAuthed, endSession, endSessionsOf, setCookie, lockedMinutes, recordFail, clearFails, ipOf,
  envManaged: () => !!envPw(), signupAllowed: () => /^(1|true|yes)$/i.test(process.env.ALLOW_SIGNUP || ''),
  isAdmin: (id) => id === ADMIN,
};
