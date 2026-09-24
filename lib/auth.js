// Đăng nhập 1 mật khẩu: phiên lưu bằng cookie HttpOnly, giới hạn thử sai theo IP.
// Mật khẩu lấy từ biến môi trường APP_PASSWORD (ưu tiên) hoặc đặt trong Cài đặt (lưu dạng băm scrypt).
const crypto = require('crypto');
const store = require('./store');

const SESSION_MS = 30 * 864e5;
const sha = (s) => crypto.createHash('sha256').update(s).digest();
const eq = (a, b) => a.length === b.length && crypto.timingSafeEqual(a, b);
const envPw = () => process.env.APP_PASSWORD || '';

const enabled = () => !!(envPw() || store.get().settings.passwordHash);

function hashPw(pw) {
  const salt = crypto.randomBytes(16);
  return `${salt.toString('hex')}:${crypto.scryptSync(pw, salt, 64).toString('hex')}`;
}
function verify(pw) {
  if (envPw()) return eq(sha(pw), sha(envPw()));
  const [s, h] = (store.get().settings.passwordHash || '').split(':');
  if (!s || !h) return false;
  return eq(crypto.scryptSync(pw, Buffer.from(s, 'hex'), 64), Buffer.from(h, 'hex'));
}
function setPassword(pw) { store.get().settings.passwordHash = hashPw(pw); store.save(); }

// ----- Phiên đăng nhập (chỉ lưu bản băm của token) -----
const sessions = () => store.get().state.sessions || (store.get().state.sessions = {});
function cookie(req, name) {
  for (const part of (req.headers.cookie || '').split(';')) {
    const i = part.indexOf('=');
    if (part.slice(0, i).trim() === name) { try { return decodeURIComponent(part.slice(i + 1).trim()); } catch { return ''; } }
  }
  return '';
}
function newSession(reset) {
  const S = sessions(), now = Date.now();
  if (reset) for (const k of Object.keys(S)) delete S[k];
  for (const k of Object.keys(S)) if (S[k] < now) delete S[k];
  const t = crypto.randomBytes(32).toString('hex');
  S[sha(t).toString('hex')] = now + SESSION_MS;
  store.save();
  return t;
}
function isAuthed(req) {
  if (!enabled()) return true;
  const t = cookie(req, 'sid');
  const exp = t && sessions()[sha(t).toString('hex')];
  return !!exp && exp > Date.now();
}
function endSession(req) { const t = cookie(req, 'sid'); if (t) { delete sessions()[sha(t).toString('hex')]; store.save(); } }
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
  if (process.env.FLY_APP_NAME) return 'fly-client-ip'; // Fly.io
  if (process.env.RENDER) return 'cf-connecting-ip'; // Render (đứng sau Cloudflare)
  return '';
}
// Sau proxy: dùng tiêu đề trên; nếu không có thì lấy phần tử CUỐI của X-Forwarded-For (do proxy gần nhất thêm vào; phần tử
// đầu là chỗ người dùng có thể điền giả).
function ipOf(req) {
  if (process.env.TRUST_PROXY || process.env.RENDER || process.env.FLY_APP_NAME) {
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

module.exports = { enabled, verify, setPassword, newSession, isAuthed, endSession, setCookie, lockedMinutes, recordFail, clearFails, ipOf, envManaged: () => !!envPw() };
