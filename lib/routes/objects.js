// Camp / nhóm QC: danh sách, số liệu theo khoảng ngày, bật/tắt, ngân sách, nhật ký và hoàn tác.
const express = require('express');
const store = require('../store');
const fb = require('../fb');
const engine = require('../engine');
const { bad } = require('./common');

const findObject = (id) => (fb.peekObjects() || []).find((o) => o.id === id);

// Ghi nhật ký cho thao tác thủ công (kể cả khi lỗi) rồi ném lại lỗi cho giao diện
async function manualAction(id, name, action, okDetail, after, fn) {
  const s = store.get().settings;
  const cur = findObject(id);
  const base = { kind: 'manual', source: 'Thủ công', name: name || id, target: { id, name: name || id, level: cur && cur.level, ...(cur && cur.accountId ? { accountId: cur.accountId, accountName: cur.accountName } : {}) }, action, before: fb.snapshot(cur), mode: s.mock ? 'mock' : s.dryRun ? 'dry' : 'live' };
  try {
    await fn();
    store.log({ ...base, detail: okDetail, ok: true, after });
  } catch (e) {
    store.log({ ...base, detail: e.message, ok: false, error: fb.describeError(e) });
    throw e;
  }
}

module.exports = ({ V, D }) => {
  const r = express.Router();

  r.get('/objects', async (req, res) => {
    res.json({ items: await fb.listObjects(req.query.refresh === '1'), ...fb.objectsMeta() });
  });

  // Số liệu theo khoảng ngày cho Tổng quan: ?range=last_7d hoặc ?since=2026-09-01&until=2026-09-20 (trống = hôm nay).
  // Không đụng tới danh sách camp/engine: chỉ trả { [id]: metrics }, giao diện ghép vào bảng.
  r.get('/insights', async (req, res) => {
    const today = D.todayIn(store.get().settings.timezone);
    const parsed = D.parseRange(req.query, today);
    if (!parsed.ok) return res.status(400).json({ error: parsed.error });
    const range = D.resolveRange(parsed.spec, today);
    const q = { key: parsed.key, fbParams: D.fbParams(parsed.spec, today), days: range.days };
    const got = await fb.rangeData(q, req.query.refresh === '1');
    const meta = fb.objectsMeta();
    res.json({ range: parsed.spec, key: parsed.key, since: range.since, until: range.until, days: range.days, at: got.at || null, stale: got.stale,
      blockedUntil: meta.blockedUntil, usage: meta.usage, accountErrors: meta.accountErrors, metrics: got.data });
  });

  r.post('/objects/:id/status', async (req, res) => {
    const { id } = req.params, b = req.body;
    await manualAction(id, b.name, { type: b.on ? 'on' : 'off' }, b.on ? 'Bật camp' : 'Tắt camp', { status: b.on ? 'ACTIVE' : 'PAUSED' }, () => fb.setStatus(id, !!b.on));
    res.json({ ok: true });
  });

  r.post('/objects/:id/budget', async (req, res) => {
    const { id } = req.params, b = req.body;
    const bv = V.validateBudget(b.amount);
    if (!bv.ok) return bad(res, bv);
    const cur = findObject(id);
    if (cur && cur.dailyBudget == null) return res.status(400).json({ error: 'Mục này không có ngân sách riêng (đang dùng ngân sách chiến dịch - CBO). Hãy chỉnh ở cấp có ngân sách.' });
    await manualAction(id, b.name, { type: 'budget', mode: 'set', value: bv.value }, `Đặt ngân sách ${bv.value.toLocaleString('vi-VN')}`, { dailyBudget: bv.value }, () => fb.setBudget(id, bv.value));
    res.json({ ok: true });
  });

  r.get('/logs', (req, res) => res.json(store.get().logs.slice(0, 300)));

  // Hoàn tác một thay đổi đã ghi trong nhật ký
  r.post('/logs/:id/undo', async (req, res) => {
    res.json({ ok: true, entry: await engine.undoLog(req.params.id, { force: !!req.body.force }) });
  });

  return r;
};
