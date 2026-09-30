// Đăng nhập / đăng xuất / đổi mật khẩu, quản lý tài khoản (chỉ admin), và /health cho dịch vụ theo dõi.
const express = require('express');
const auth = require('../auth');
const watch = require('../watch');
const { requireAdmin } = require('../middleware');
const { bad } = require('./common');

module.exports = ({ V }) => {
  const r = express.Router();
  const pwError = (pw, cur = '') => { const pv = V.validatePassword(pw, cur); return pv.ok ? null : pv; };
  const login = (req, res, u) => { auth.clearFails(req); auth.setCookie(req, res, auth.newSession(u.id), 30 * 86400); };

  // required: phải đăng nhập; setup: chưa có tài khoản nào (Cài đặt → Bảo mật đặt mật khẩu admin); signup: cho tự đăng ký
  r.get('/auth', (req, res) => {
    const uid = auth.userIdOf(req);
    res.json({ required: auth.enabled(), authed: uid != null, envManaged: auth.envManaged(), setup: !auth.enabled(), signup: auth.signupAllowed(), user: uid != null ? auth.publicUser(auth.byId(uid)) || { id: uid, username: 'admin', name: '', admin: true } : null });
  });
  // Cho UptimeRobot / Render: 503 khi vòng lịch/rule không chạy xong lượt nào trong 5 phút (web còn sống chưa chắc lịch còn chạy)
  r.get('/health', (req, res) => { const h = watch.health(); res.status(h.ok ? 200 : 503).json(h); });

  r.post('/login', async (req, res) => {
    const wait = auth.lockedMinutes(req);
    if (wait) return res.status(429).json({ error: `Nhập sai quá nhiều lần. Thử lại sau ${wait} phút.` });
    if (!auth.enabled()) return res.json({ ok: true });
    const u = auth.verify(String(req.body.username || ''), String(req.body.password || ''));
    if (u) { login(req, res, u); return res.json({ ok: true }); }
    auth.recordFail(req);
    await new Promise((ok) => setTimeout(ok, 600)); // làm chậm dò mật khẩu
    res.status(401).json({ error: 'Sai tên đăng nhập hoặc mật khẩu' });
  });

  // Tự đăng ký (chỉ khi ALLOW_SIGNUP=true): tài khoản mới có dữ liệu trống của riêng mình
  r.post('/register', (req, res) => {
    if (!auth.signupAllowed()) return res.status(403).json({ error: 'Tool không cho tự đăng ký. Nhờ admin tạo tài khoản.' });
    const wait = auth.lockedMinutes(req);
    if (wait) return res.status(429).json({ error: `Thử quá nhiều lần. Thử lại sau ${wait} phút.` });
    const b = req.body, pv = pwError(String(b.password || ''));
    if (pv) return bad(res, { first: pv.first, errors: { password: pv.errors.newPassword } });
    auth.recordFail(req); // mỗi lần đăng ký tính như một lần thử sai: chặn tạo hàng loạt tài khoản từ một IP
    const u = auth.createUser(b.username, b.name, String(b.password));
    auth.setCookie(req, res, auth.newSession(u.id), 30 * 86400);
    res.json({ ok: true });
  });

  r.post('/logout', (req, res) => {
    auth.endSession(req);
    auth.setCookie(req, res, '', 0);
    res.json({ ok: true });
  });

  // Đổi mật khẩu của chính mình. Chưa có tài khoản nào: đặt mật khẩu admin (bật đăng nhập).
  r.post('/password', (req, res) => {
    const uid = req.userId;
    if (auth.isAdmin(uid) && auth.envManaged()) return res.status(400).json({ error: 'Mật khẩu admin đang được đặt bằng biến môi trường APP_PASSWORD trên server, không đổi ở đây được.' });
    const b = req.body, next = String(b.newPassword || ''), cur = String(b.currentPassword || '');
    const pv = pwError(next, cur);
    if (pv) return bad(res, pv);
    if (auth.enabled()) {
      const me = auth.byId(uid);
      if (!me || !auth.verify(me.username, cur)) return res.status(400).json({ error: 'Mật khẩu hiện tại không đúng.', errors: { currentPassword: 'Mật khẩu hiện tại không đúng.' } });
    }
    auth.setPassword(uid, next);
    auth.setCookie(req, res, auth.newSession(uid, true), 30 * 86400); // đăng xuất mọi thiết bị khác
    res.json({ ok: true });
  });

  // ----- Quản lý tài khoản (chỉ admin) -----
  r.get('/users', requireAdmin, (req, res) => res.json(auth.list()));
  r.post('/users', requireAdmin, (req, res) => {
    const b = req.body, pv = pwError(String(b.password || ''));
    if (pv) return bad(res, { first: pv.first, errors: { password: pv.errors.newPassword } });
    res.json(auth.publicUser(auth.createUser(b.username, b.name, String(b.password))));
  });
  // Đặt lại mật khẩu cho người quên: đăng xuất họ khỏi mọi thiết bị
  r.post('/users/:id/password', requireAdmin, (req, res) => {
    const id = Number(req.params.id), u = auth.byId(id);
    if (!u || auth.isAdmin(id)) return res.status(404).json({ error: 'Không tìm thấy tài khoản' });
    const pv = pwError(String(req.body.password || ''));
    if (pv) return bad(res, { first: pv.first, errors: { password: pv.errors.newPassword } });
    auth.setPassword(id, String(req.body.password));
    auth.endSessionsOf(id);
    res.json({ ok: true });
  });
  r.delete('/users/:id', requireAdmin, (req, res) => {
    auth.deleteUser(Number(req.params.id));
    res.json({ ok: true });
  });

  return r;
};
