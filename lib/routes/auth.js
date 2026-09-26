// Đăng nhập / đăng xuất / đổi mật khẩu.
const express = require('express');
const auth = require('../auth');
const { bad } = require('./common');

module.exports = ({ V }) => {
  const r = express.Router();

  r.get('/auth', (req, res) => res.json({ required: auth.enabled(), authed: auth.isAuthed(req), envManaged: auth.envManaged() }));

  r.post('/login', async (req, res) => {
    const wait = auth.lockedMinutes(req);
    if (wait) return res.status(429).json({ error: `Nhập sai quá nhiều lần. Thử lại sau ${wait} phút.` });
    if (!auth.enabled()) return res.json({ ok: true });
    if (auth.verify(String(req.body.password || ''))) {
      auth.clearFails(req);
      auth.setCookie(req, res, auth.newSession(), 30 * 86400);
      return res.json({ ok: true });
    }
    auth.recordFail(req);
    await new Promise((ok) => setTimeout(ok, 600)); // làm chậm dò mật khẩu
    res.status(401).json({ error: 'Sai mật khẩu' });
  });

  r.post('/logout', (req, res) => {
    auth.endSession(req);
    auth.setCookie(req, res, '', 0);
    res.json({ ok: true });
  });

  r.post('/password', (req, res) => {
    if (auth.envManaged()) return res.status(400).json({ error: 'Mật khẩu đang được đặt bằng biến môi trường APP_PASSWORD trên server, không đổi ở đây được.' });
    const b = req.body, next = String(b.newPassword || '');
    const pv = V.validatePassword(next, String(b.currentPassword || ''));
    if (!pv.ok) return bad(res, pv);
    if (auth.enabled() && !auth.verify(String(b.currentPassword || ''))) return res.status(400).json({ error: 'Mật khẩu hiện tại không đúng.' });
    auth.setPassword(next);
    auth.setCookie(req, res, auth.newSession(true), 30 * 86400); // đăng xuất mọi thiết bị khác
    res.json({ ok: true });
  });

  return r;
};
