// Lịch tự động, rule và báo cáo Telegram.
const express = require('express');
const store = require('../store');
const fb = require('../fb');
const engine = require('../engine');
const notify = require('../notify');
const { bad, upsert } = require('./common');

const MAX_ITEMS = 200;

// Ngữ cảnh cho luật kiểm tra lịch/rule: camp hiện có, danh sách cùng loại (kiểm tra trùng), mục tiêu theo tài khoản
const validateCtx = (extra) => ({ objs: fb.peekObjects(), accountTargets: store.get().settings.accountTargets || {}, accounts: fb.objectsMeta().accounts, ...extra });

// Lưu (thêm/sửa) một lịch hoặc rule đã qua kiểm tra
function saveItem(res, list, r) {
  if (!r.ok) return bad(res, r);
  if (!r.value.id && list.length >= MAX_ITEMS) return res.status(400).json({ error: `Đã đạt giới hạn ${MAX_ITEMS} mục. Hãy xoá bớt trước khi thêm mới.` });
  res.json({ ...upsert(list, r.value), warnings: r.warnings });
}

function removeItem(res, list, id) {
  const i = list.findIndex((x) => x.id === id);
  if (i >= 0) list.splice(i, 1);
  store.save();
  res.json({ ok: true });
}

module.exports = ({ V }) => {
  const r = express.Router();

  // ----- Lịch -----
  r.post('/schedules', (req, res) => {
    const list = store.get().schedules;
    saveItem(res, list, V.validateSchedule(req.body, validateCtx({ schedules: list })));
  });
  r.delete('/schedules/:id', (req, res) => removeItem(res, store.get().schedules, req.params.id));
  r.post('/schedules/:id/run', async (req, res) => {
    const s = store.get().schedules.find((x) => x.id === req.params.id);
    if (!s) return res.status(404).json({ error: 'Không tìm thấy lịch' });
    if (await engine.runSchedule(s) === 'blocked') return res.status(429).json({ error: 'Facebook đang giới hạn số lần gọi nên lịch chưa chạy. Thử lại sau vài phút.', rateLimited: true });
    res.json({ ok: true });
  });

  // ----- Rule -----
  r.get('/rules/activity', (req, res) => res.json(engine.ruleActivity()));
  r.post('/rules/run', async (req, res) => { await engine.runRules(); res.json({ ok: true }); });
  // Xem trước: rule (chưa lưu) đang khớp camp nào ngay bây giờ — không thay đổi gì
  r.post('/rules/preview', async (req, res) => {
    const v = V.validateRule({ ...req.body, enabled: true }, validateCtx({ rules: [] }));
    if (!v.ok) return bad(res, v);
    res.json({ ...(await engine.previewRule(v.value)), warnings: v.warnings });
  });
  r.post('/rules', (req, res) => {
    const list = store.get().rules;
    saveItem(res, list, V.validateRule(req.body, validateCtx({ rules: list })));
  });
  r.delete('/rules/:id', (req, res) => removeItem(res, store.get().rules, req.params.id));

  // ----- Báo cáo -----
  r.post('/report', async (req, res) => {
    const out = notify.reply(await engine.sendReport());
    res.status(out.status).json(out.body);
  });

  return r;
};
