// Kết nối Facebook: thử kết nối, liệt kê tài khoản, gia hạn token, đăng nhập bằng Facebook (OAuth).
const express = require('express');
const crypto = require('crypto');
const store = require('../store');
const fb = require('../fb');

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

async function oauthCallback(req, res) {
  const q = req.query, st = q.state || '';
  const pending = oauthStates.get(st);
  oauthStates.delete(st);
  // Chỉ đưa mã kết quả lên URL; nội dung lỗi lấy qua /api/fb/oauth (tránh người ngoài chèn chữ tuỳ ý vào giao diện)
  const back = (result) => res.status(302).set('Location', OAUTH_BACK + result).end();
  if (!pending || pending.exp < Date.now()) return back('expired');
  if (q.error || !q.code) return back('cancel'); // người dùng bấm Huỷ trên Facebook
  const s = store.get().settings;
  try {
    s.accessToken = await fb.exchangeCode({ appId: pending.appId, appSecret: s.fbAppSecret, redirectUri: pending.redirectUri, code: q.code });
    store.save();
    fb.resetCache();
    oauthError = '';
    return back('ok');
  } catch (e) {
    oauthError = e.message;
    return back('fail');
  }
}

module.exports = ({ V }) => {
  const r = express.Router();

  // Không cần đăng nhập (xem PUBLIC_API trong lib/middleware.js)
  r.get('/fb/callback', oauthCallback);

  const testConnection = async (req, res) => {
    try { res.json(await fb.testConnection()); } catch (e) { res.json({ ok: false, error: e.message }); }
  };
  r.get(['/test-connection', '/connection'], testConnection);
  r.post(['/test-connection', '/connection'], testConnection);

  // Liệt kê tài khoản quảng cáo từ token (token mới dán chưa cần lưu).
  r.post('/fb/accounts', async (req, res) => {
    const token = (req.body.token || '').trim() || store.get().settings.accessToken;
    const tm = V.checkToken(token);
    if (tm) return res.status(400).json({ error: tm });
    res.json(await fb.listAccounts(token));
  });

  // Đổi token ngắn hạn thành ~60 ngày. App ID/Secret chỉ dùng 1 lần, không lưu.
  r.post('/fb/extend', async (req, res) => {
    const b = req.body, s = store.get().settings;
    const token = (b.token || '').trim() || s.accessToken;
    const em = V.checkToken(token) || V.checkAppId(b.appId) || V.checkAppSecret(b.appSecret);
    if (em) return res.status(400).json({ error: em });
    const long = await fb.extendToken(String(b.appId).trim(), String(b.appSecret).trim(), token);
    s.accessToken = long; store.save();
    res.json({ ok: true, token: await fb.inspectToken(long) });
  });

  // Địa chỉ cần khai báo trong ứng dụng Meta + lỗi của lần đăng nhập Facebook gần nhất
  r.get('/fb/oauth', (req, res) => res.json({ redirectUri: redirectUri(req), error: oauthError }));

  // Bắt đầu đăng nhập bằng Facebook: lưu App ID/Secret (để lần sau chỉ cần bấm 1 nút) rồi trả về địa chỉ trang đăng nhập
  r.post('/fb/oauth/start', (req, res) => {
    const b = req.body, s = store.get().settings;
    const appId = String(b.appId || '').trim() || s.fbAppId;
    const appSecret = String(b.appSecret || '').trim() || (appId === s.fbAppId ? s.fbAppSecret : ''); // đổi ứng dụng thì phải nhập secret mới
    const configId = 'configId' in b ? String(b.configId || '').trim() : s.fbConfigId;
    const em = V.checkAppId(appId) || (appSecret ? V.checkAppSecret(appSecret) : 'Hãy nhập App Secret') || V.checkConfigId(configId);
    if (em) return res.status(400).json({ error: em });
    Object.assign(s, { fbAppId: appId, fbAppSecret: appSecret, fbConfigId: configId });
    store.save();
    oauthError = '';
    const uri = redirectUri(req);
    res.json({ url: fb.oauthUrl({ appId, configId, redirectUri: uri, state: newOauthState(appId, uri) }) });
  });

  return r;
};
