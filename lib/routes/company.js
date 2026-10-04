// Báo cáo lên hệ thống công ty: cài đặt, kiểm tra kết nối, danh sách Team của công ty, các bản báo cáo theo mốc.
const express = require('express');
const store = require('../store');
const engine = require('../engine');
const company = require('../companyReport');
const companyApi = require('../companyApi');

let sharedMod = null;
const C = () => (sharedMod ||= import('../../shared/companyReport.mjs'));
const badReq = (res, v) => res.status(400).json({ error: v.first, errors: v.errors || {} });

module.exports = () => {
  const r = express.Router();

  r.get('/company', (req, res) => res.json({ config: company.publicConfig(), reports: store.get().companyReports }));

  r.post('/company/config', async (req, res) => {
    const S = await C();
    const cur = store.get().company;
    const v = S.validateCompanyConfig(req.body, { ...cur, has_password: !!cur.password });
    if (!v.ok) return badReq(res, v);
    Object.assign(cur, v.value);
    store.save();
    companyApi.reset(); // đổi tài khoản/địa chỉ → đăng nhập lại ở lần gọi sau
    res.json({ config: company.publicConfig() });
  });

  // Đăng nhập thử bằng tài khoản đã lưu → tên người dùng + danh sách Team để chọn
  r.post('/company/test', async (req, res) => {
    const me = await companyApi.test();
    const teams = await companyApi.listTeams();
    res.json({ ...me, teams });
  });
  r.get('/company/teams', async (req, res) => res.json({ teams: await companyApi.listTeams() }));

  // Tạo bản báo cáo của một mốc ngay (không chờ đến giờ), vd để thử hoặc làm lại sau khi đổi cấu hình
  r.post('/company/reports/build', async (req, res) => {
    const S = await C();
    const slot = Number(req.body.slot);
    if (!S.SLOTS.includes(slot)) return res.status(400).json({ error: 'Mốc báo cáo không hợp lệ' });
    if (!(store.get().company.teams || []).length) return res.status(400).json({ error: 'Chưa có Team nào. Thêm Team ở Cài đặt → Báo cáo công ty.' });
    const list = await company.createDrafts(slot, engine.localNow().date, { silent: !req.body.notify });
    res.json({ reports: store.get().companyReports, built: list.map((x) => x.id) });
  });

  r.post('/company/reports/:id', async (req, res) => res.json(await company.update(req.params.id, req.body)));
  r.post('/company/reports/:id/send', async (req, res) => res.json(await company.send(req.params.id, { source: 'Báo cáo công ty' })));
  r.post('/company/reports/:id/update', async (req, res) => res.json(await company.updateRemote(req.params.id, { reason: req.body && req.body.reason, source: 'Báo cáo công ty' })));
  // Lấy trạng thái từ công ty (đã nộp / nộp muộn, lần sửa, đã khoá) cho các bản báo cáo gần đây
  r.post('/company/sync', async (req, res) => { const n = await company.sync(); res.json({ synced: n, reports: store.get().companyReports }); });
  r.delete('/company/reports/:id', (req, res) => { company.remove(req.params.id); res.json({ ok: true }); });

  return r;
};
