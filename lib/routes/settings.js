// Trạng thái chung, cài đặt và thử kết nối Telegram.
const express = require('express');
const store = require('../store');
const fb = require('../fb');
const notify = require('../notify');
const { bad, publicSettings } = require('./common');

// Đổi nguồn dữ liệu → bỏ cache cũ
const SOURCE_KEYS = ['mock', 'adAccountId', 'adAccountIds', 'accessToken'];

module.exports = ({ V }) => {
  const r = express.Router();

  r.get('/state', (req, res) => {
    const d = store.get();
    res.json({ settings: publicSettings(), schedules: d.schedules, rules: d.rules, storage: store.status() });
  });
  r.get('/storage', (req, res) => res.json(store.status()));

  r.post('/settings', async (req, res) => {
    const settings = store.get().settings;
    const sv = V.validateSettings(req.body, settings);
    if (!sv.ok) return bad(res, sv);
    // chỉ ghi các khóa đã được kiểm tra (sv.value) — không bao giờ ghi trực tiếp từ dữ liệu client
    for (const [k, val] of Object.entries(sv.value)) if (k in settings && k !== 'passwordHash') settings[k] = val;
    store.save();
    if (SOURCE_KEYS.some((k) => k in sv.value)) fb.resetCache();
    await fb.listObjects(true).catch(() => {});
    res.json(publicSettings());
  });

  r.post('/telegram/test', async (req, res) => {
    const out = notify.reply(await notify.send('✅ Kết nối Telegram thành công — Facebook Ads Auto Tool'));
    res.status(out.status).json(out.body);
  });

  return r;
};
