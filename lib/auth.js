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
const ipOf = (req) => (process.env.TRUST_PROXY && (req.headers['x-forwarded-for'] || '').split(',')[0].trim()) || req.socket.remoteAddress;
function lockedMinutes(req) { const f = fails.get(ipOf(req)); return f && f.until > Date.now() ? Math.ceil((f.until - Date.now()) / 60000) : 0; }
function recordFail(req) {
  const k = ipOf(req), f = fails.get(k) || { n: 0, until: 0 };
  if (++f.n >= 5) { f.until = Date.now() + 15 * 60000; f.n = 0; }
  fails.set(k, f);
}
const clearFails = (req) => fails.delete(ipOf(req));

module.exports = { enabled, verify, setPassword, newSession, isAuthed, endSession, setCookie, lockedMinutes, recordFail, clearFails, envManaged: () => !!envPw() };
