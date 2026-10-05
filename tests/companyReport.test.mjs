// Báo cáo lên hệ thống công ty: luật dùng chung (shared/companyReport.mjs), gọi API công ty (lib/companyApi.js),
// tạo/gửi báo cáo (lib/companyReport.js) và nút Telegram (lib/tgbot.js).
// Hệ thống công ty và Telegram đều là server giả trong bộ nhớ (thay fetch): không bao giờ gọi hệ thống thật.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import * as S from '../shared/companyReport.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-company-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const api = require('../lib/companyApi')
const company = require('../lib/companyReport')
const bot = require('../lib/tgbot')

/* ------------------------------------------------ server giả của công ty + Telegram */
const BASE = 'https://mkt.test.local'
// Mật khẩu giả của server giả (ghép lúc chạy để công cụ quét bí mật không nhầm là mật khẩu thật)
const GOOD_LOGIN = ['dung', 'mat', 'khau'].join('-')
let co, tgSent
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } })
globalThis.fetch = async (url, init = {}) => {
  const u = new URL(String(url))
  const body = init.body ? JSON.parse(init.body) : null
  if (u.hostname === 'api.telegram.org') { tgSent.push({ method: u.pathname.split('/').pop(), body }); return json({ ok: true, result: { message_id: 900 } }) }
  assert.equal(u.origin, BASE, 'chỉ gọi đúng địa chỉ hệ thống công ty đã cài')
  const p = u.pathname.replace(/^\/api/, '')
  co.calls.push({ method: init.method || 'GET', path: p, query: Object.fromEntries(u.searchParams), body, headers: init.headers || {} })
  if (p === '/auth/login') {
    co.logins++
    if (body.password !== GOOD_LOGIN) return json({ error: 'Email hoặc mật khẩu không đúng.' }, 401)
    co.session = `sess${co.logins}`
    return json({ user: { id: 'u1', name: 'Nguyễn Minh Phương', role: 'MKT', must_change: 0 }, csrf: `csrf${co.logins}` }, 200, { 'set-cookie': `wellday_session=${co.session}; Path=/; HttpOnly; Secure` })
  }
  const h = init.headers || {}
  if (h.Cookie !== `wellday_session=${co.session}` || h['X-CSRF-Token'] !== `csrf${co.logins}` || co.expire) { co.expire = false; return json({ error: 'Phiên đăng nhập đã hết hạn.' }, 401) }
  if (p === '/teams') return json([{ id: 'team-1', code: 'CT01', name: 'WDC - Hoạt Huyết', status: 'ACTIVE' }, { id: 'team-2', code: 'CT02', name: 'Khác', status: 'ACTIVE' }])
  if (p === '/reports' && (init.method || 'GET') === 'GET') return json(co.reports.filter((r) => r.team_id === u.searchParams.get('team_id') && r.date >= u.searchParams.get('from') && r.date <= u.searchParams.get('to')))
  if (p === '/reports' && init.method === 'POST') {
    if (co.failPost) return json({ error: 'Dữ liệu không hợp lệ.' }, 400)
    const old = co.reports.find((x) => x.team_id === body.team_id && x.date === body.date && x.slot === body.slot)
    if (old) { // sửa báo cáo đã có: như web công ty, cần đúng lần sửa + lý do, đã khoá thì không cho
      if (old.locked) return json({ error: 'Báo cáo đã khóa.' }, 403)
      if (body.revision !== old.revision) return json({ error: 'Báo cáo đã được cập nhật bởi người khác.' }, 409)
      if (!body.reason) return json({ error: 'Cần lý do chỉnh sửa.' }, 400)
      const { reason, revision, ...rest } = body
      Object.assign(old, rest, { revision: old.revision + 1, locked: true })
      co.updates.push(body)
      return json(old)
    }
    const r = { id: `rep-${co.reports.length + 1}`, user_id: 'u1', status: 'SUBMITTED', revision: 1, locked: false, ...body }
    co.reports.push(r)
    return json(r)
  }
  return json({ error: 'Not found' }, 404)
}

const TEAM = { id: 'team-1', code: 'CT01', name: 'WDC - Hoạt Huyết', accountIds: ['mock_a'], match: '' }
beforeEach(() => {
  const d = store.get()
  d.logs.length = 0
  d.companyReports.length = 0
  d.state.companyFired = {}
  Object.assign(d.settings, { mock: true, dryRun: true, telegramToken: '123:abc', telegramChatId: '42', telegramCommands: true })
  Object.assign(d.company, { enabled: true, mode: 'approve', slots: [9, 12, 17, 22], leadMin: 0, baseUrl: BASE, email: 'mkt@congty.vn', password: GOOD_LOGIN, teams: [TEAM] })
  fb.resetMock(); fb.resetCache(); api.reset(); bot.reset()
  co = { calls: [], logins: 0, session: '', reports: [], updates: [], expire: false, failPost: false }
  tgSent = []
})

/* ------------------------------------------------------------ luật dùng chung */
test('mốc 9h là báo cáo 9h của ngày hôm qua (chạy sáng nay, cập nhật vào bản ghi đã có); các mốc khác báo hôm nay', () => {
  assert.equal(S.reportDate(9, '2026-10-01'), '2026-09-30')
  assert.equal(S.submitDate(9, '2026-09-30'), '2026-10-01')
  assert.equal(S.reportDate(17, '2026-10-01'), '2026-10-01')
  assert.equal(S.submitDate(17, '2026-10-01'), '2026-10-01')
  assert.equal(S.updatesExisting(9), true)
  assert.equal(S.updatesExisting(12), false)
  assert.equal(S.closeReason('2026-10-04'), 'Chốt số liệu cả ngày 04/10')
  assert.equal(S.validateReason(S.closeReason('2026-10-04')), '')
  assert.equal(S.rangeOf(9), 'yesterday')
  assert.equal(S.rangeOf(22), 'today')
})

test('chiến dịch thuộc Team: theo tài khoản và/hoặc từ khoá tên; Team trống không có chiến dịch nào', () => {
  const objs = [
    { id: '1', level: 'campaign', accountId: 'A', name: 'CT01 Hoạt huyết - Mess' },
    { id: '2', level: 'campaign', accountId: 'A', name: 'CT02 Xương khớp' },
    { id: '3', level: 'campaign', accountId: 'B', name: 'ct01 retarget' },
    { id: '4', level: 'adset', accountId: 'A', name: 'CT01 nhóm' },
  ]
  assert.deepEqual(S.teamCampaigns({ accountIds: ['A'] }, objs).map((o) => o.id), ['1', '2'])
  assert.deepEqual(S.teamCampaigns({ match: 'CT01' }, objs).map((o) => o.id), ['1', '3'])
  assert.deepEqual(S.teamCampaigns({ accountIds: ['A'], match: 'ct01, xương' }, objs).map((o) => o.id), ['1', '2'])
  assert.deepEqual(S.teamCampaigns({ accountIds: [], match: '' }, objs), [])
  assert.deepEqual(S.overlaps([{ code: 'X', accountIds: ['A'] }, { code: 'Y', match: 'ct01' }], objs), [{ name: 'CT01 Hoạt huyết - Mess', teams: ['X', 'Y'] }])
})

test('cộng số: tin nhắn = cuộc trò chuyện, SĐT = khách hàng tiềm năng, Đơn = kết quả, DSO = doanh thu, làm tròn', () => {
  const camps = [{ id: '1' }, { id: '2' }, { id: '3' }]
  const data = { 1: { spend: 100.4, conversations: 3, leads: 2, results: 2, revenue: 500000.4, impressions: 1000, clicks: 10 }, 2: { spend: 200.4, conversations: 1, leads: 0, results: 1, revenue: 250000, impressions: 500, clicks: 5 } }
  assert.deepEqual(S.sumMetrics(camps, data), { spend: 301, messages: 4, phones: 2, orders: 3, dso_after: 750000, impressions: 1500, clicks: 15 })
})

test('số bất thường (không tự gửi): thiếu số, Team không khớp chiến dịch, có đơn mà doanh thu = 0', () => {
  const metrics = { spend: 1, messages: 2, phones: 3, orders: 4, dso_after: 5, impressions: 6, clicks: 7 }
  assert.deepEqual(S.anomalies({ metrics, campaigns: ['a'] }), [])
  assert.deepEqual(S.anomalies({ metrics: { ...metrics, orders: 0, dso_after: 0 }, campaigns: ['a'] }), [])
  assert.match(S.anomalies({ metrics: { ...metrics, dso_after: 0 }, campaigns: ['a'] })[0], /doanh thu bằng 0/)
  assert.match(S.anomalies({ metrics, campaigns: [] })[0], /không khớp chiến dịch/)
  assert.match(S.anomalies({ metrics: { ...metrics, clicks: null }, campaigns: ['a'] })[0], /Còn thiếu Lượt nhấp/)
  assert.equal(S.canSendMode('auto'), true)
  assert.equal(S.canSendMode('preview'), false)
})

test('kiểm tra cài đặt: mật khẩu trống giữ mật khẩu cũ, bật phải có tài khoản + Team, Team phải có phạm vi', () => {
  assert.equal(S.validateCompanyConfig({ enabled: true, email: 'a@b.vn', teams: [TEAM] }, { has_password: true, slots: [9] }).ok, true)
  assert.match(S.validateCompanyConfig({ enabled: true, email: 'a@b.vn', teams: [TEAM] }, { slots: [9] }).errors.password, /mật khẩu/)
  assert.match(S.validateCompanyConfig({ enabled: true, teams: [] }, { email: 'a@b.vn', has_password: true, slots: [9] }).errors.teams, /ít nhất một Team/)
  assert.match(S.validateCompanyConfig({ teams: [{ id: 'team-1', code: 'CT01' }] }).errors.teams, /CT01: hãy chọn tài khoản/)
  assert.match(S.validateCompanyConfig({ teams: [TEAM, TEAM] }).errors.teams, /hai lần/)
  assert.match(S.validateCompanyConfig({ baseUrl: 'http://mkt.companyos.site' }).errors.baseUrl, /https/)
  assert.match(S.validateCompanyConfig({ slots: [8] }).errors.slots, /không hợp lệ/)
  const v = S.validateCompanyConfig({ password: '', slots: [22, 9], teams: [{ ...TEAM, match: ' CT01 ; hoạt huyết ' }] })
  assert.equal('password' in v.value, false)
  assert.deepEqual(v.value.slots, [9, 22])
  assert.equal(v.value.teams[0].match, 'ct01, hoạt huyết')
})

test('nhắn Đơn/DSO trên Telegram: hiểu dấu chấm nghìn, "tr", "k"; sai dạng thì null', () => {
  assert.deepEqual(S.parseOrdersDso('12 3.500.000'), { orders: 12, dso_after: 3500000 })
  assert.deepEqual(S.parseOrdersDso('đơn 12 dso 3,5tr'), { orders: 12, dso_after: 3500000 })
  assert.deepEqual(S.parseOrdersDso('0 0'), { orders: 0, dso_after: 0 })
  assert.deepEqual(S.parseOrdersDso('7, 850k'), { orders: 7, dso_after: 850000 })
  assert.equal(S.parseOrdersDso('12'), null)
  assert.equal(S.parseOrdersDso('1 2 3'), null)
  assert.equal(S.parseOrdersDso('abc'), null)
})

test('sửa báo cáo: số nguyên không âm, trống = chưa nhập; body gửi đúng tên trường của công ty', () => {
  assert.match(S.validateReportPatch({ metrics: { orders: -1 } }).errors.orders, /không âm/)
  assert.match(S.validateReportPatch({ metrics: { dso_after: 1.5 } }).errors.dso_after, /số nguyên/)
  assert.deepEqual(S.validateReportPatch({ metrics: { orders: '' } }).value.metrics, { orders: null })
  const r = { teamId: 't', date: '2026-10-04', slot: 17, metrics: { spend: 1, messages: 2, phones: 3, orders: 4, dso_after: 5, impressions: 6, clicks: 7 }, notes: 'n' }
  assert.deepEqual(S.payloadOf(r), { team_id: 't', date: '2026-10-04', slot: 17, metrics: { spend: 1, messages: 2, phones: 3, orders: 4, dso_after: 5, impressions: 6, clicks: 7 }, notes: 'n', issue: '', resolution: '' })
  assert.deepEqual(S.missingMetrics({ metrics: { ...r.metrics, orders: null } }).map((m) => m.key), ['orders'])
})

/* ------------------------------------------------------- tạo báo cáo theo mốc */
test('đến mốc: tạo báo cáo 1 lần cho mỗi Team, mốc 9h ghi ngày hôm qua, báo Telegram kèm nút', async () => {
  await company.tick({ date: '2026-10-05', minutes: 9 * 60 + 3 })
  await company.tick({ date: '2026-10-05', minutes: 9 * 60 + 5 }) // vòng sau trong cùng mốc: không tạo lại
  const list = store.get().companyReports
  assert.equal(list.length, 1)
  const r = list[0]
  assert.equal(r.date, '2026-10-04', 'mốc 9h sáng 05/10 là báo cáo 9h ngày 04/10')
  assert.equal(r.dateRule, S.DATE_RULE)
  assert.equal(r.slot, 9)
  assert.equal(r.status, 'pending')
  assert.equal(typeof r.metrics.orders, 'number', 'Đơn lấy từ kết quả Facebook')
  assert.ok(r.campaigns.length > 0 && r.campaigns.length <= 4, 'chỉ các camp của tài khoản mẫu A')
  assert.ok(r.metrics.spend > 0)
  const msgs = tgSent.filter((x) => x.method === 'sendMessage')
  assert.equal(msgs.length, 1)
  assert.match(msgs[0].body.text, /Báo cáo công ty · 9h ngày 04\/10<\/b> · chốt cả ngày, cập nhật sáng 05\/10/)
  assert.match(msgs[0].body.text, /Đơn hàng: <b>/)
  assert.deepEqual(msgs[0].body.reply_markup.inline_keyboard[0].map((b) => b.callback_data), [`ce:${r.id}`, `cs:${r.id}`])
  assert.equal(co.calls.length, 0, 'tạo báo cáo không gọi hệ thống công ty')
})

test('ngoài giờ mốc, đang tắt, hoặc mốc không được chọn thì không tạo gì', async () => {
  await company.tick({ date: '2026-10-04', minutes: 9 * 60 + 30 })
  store.get().company.slots = [12]
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 })
  store.get().company.enabled = false
  await company.tick({ date: '2026-10-04', minutes: 12 * 60 })
  assert.equal(store.get().companyReports.length, 0)
})

test('chế độ Chỉ xem: báo Telegram không có nút, và không gửi được lên công ty', async () => {
  store.get().company.mode = 'preview'
  const [r] = await company.createDrafts(17, '2026-10-04')
  const msg = tgSent.find((x) => x.method === 'sendMessage')
  assert.match(msg.body.text, /Chế độ Chỉ xem/)
  assert.equal(msg.body.reply_markup, undefined)
  await company.update(r.id, { metrics: { orders: 3, dso_after: 900000 } })
  store.get().settings.mock = false
  await assert.rejects(company.send(r.id), /Chỉ xem/)
  assert.equal(co.calls.length, 0)
})

test('làm mới mốc: giữ số đã sửa tay và ghi chú, số chưa sửa lấy lại từ Facebook', async () => {
  const [r] = await company.createDrafts(12, '2026-10-04', { silent: true })
  await company.update(r.id, { metrics: { orders: 5, dso_after: 1000000 }, notes: 'ok' })
  assert.deepEqual(r.edited.sort(), ['dso_after', 'orders'])
  r.metrics.spend = -1
  const [again] = await company.createDrafts(12, '2026-10-04', { silent: true })
  assert.equal(again.id, r.id)
  assert.equal(again.metrics.orders, 5)
  assert.equal(again.metrics.dso_after, 1000000)
  assert.ok(again.metrics.spend > 0, 'số chưa sửa được làm mới')
  assert.equal(again.notes, 'ok')
  assert.equal(store.get().companyReports.length, 1)
})

/* ------------------------------------------------------------- gửi lên công ty */
async function readyDraft() {
  const [r] = await company.createDrafts(17, '2026-10-04', { silent: true })
  await company.update(r.id, { metrics: { orders: 4, dso_after: 2500000 }, notes: 'Ổn' })
  store.get().settings.mock = false
  return r
}

test('gửi: đăng nhập, kiểm tra mốc chưa có báo cáo, rồi POST đúng body kèm cookie + CSRF; ghi Nhật ký', async () => {
  const r = await readyDraft()
  await company.send(r.id)
  assert.deepEqual(co.calls.map((c) => `${c.method} ${c.path}`), ['POST /auth/login', 'GET /reports', 'POST /reports'])
  const post = co.calls[2]
  assert.equal(post.headers.Cookie, 'wellday_session=sess1')
  assert.equal(post.headers['X-CSRF-Token'], 'csrf1')
  assert.deepEqual(post.body, { team_id: 'team-1', date: '2026-10-04', slot: 17, metrics: { ...r.metrics }, notes: 'Ổn', issue: '', resolution: '' })
  assert.equal(co.calls[1].query.team_id, 'team-1')
  assert.equal(r.status, 'sent')
  assert.equal(r.remoteId, 'rep-1')
  const l = store.get().logs[0]
  assert.equal(l.kind, 'company')
  assert.equal(l.ok, true)
  assert.doesNotMatch(JSON.stringify(store.get().logs), new RegExp(GOOD_LOGIN), 'mật khẩu không bao giờ vào nhật ký')
  await assert.rejects(company.send(r.id), /đã gửi rồi/)
  assert.equal(co.reports.length, 1)
})

test('mốc đã có báo cáo trên công ty: không gửi đè, đánh dấu và báo rõ', async () => {
  const r = await readyDraft()
  co.reports.push({ id: 'old', team_id: 'team-1', date: '2026-10-04', slot: 17, user_id: 'u1', status: 'SUBMITTED' })
  await assert.rejects(company.send(r.id), /đã có báo cáo/)
  assert.equal(r.status, 'exists')
  assert.equal(co.calls.filter((c) => c.method === 'POST' && c.path === '/reports').length, 0)
})

test('thiếu Đơn/DSO hoặc đang dùng dữ liệu giả thì không gửi', async () => {
  const [r] = await company.createDrafts(17, '2026-10-04', { silent: true })
  await assert.rejects(company.send(r.id), /dữ liệu giả/)
  store.get().settings.mock = false
  await company.update(r.id, { metrics: { orders: '', dso_after: '' } })
  await assert.rejects(company.send(r.id), /Còn thiếu: Số đơn hàng, DSO sau VAT/)
  assert.equal(co.calls.length, 0)
})

test('phiên hết hạn: tự đăng nhập lại 1 lần rồi gửi tiếp; sai mật khẩu thì báo lỗi, đánh dấu thất bại', async () => {
  const r = await readyDraft()
  await api.login()
  co.expire = true
  await company.send(r.id)
  assert.equal(co.logins, 2)
  assert.equal(r.status, 'sent')

  const [r2] = await company.createDrafts(22, '2026-10-04', { silent: true })
  await company.update(r2.id, { metrics: { orders: 1, dso_after: 1 } })
  store.get().company.password = 'sai'
  await assert.rejects(company.send(r2.id), /từ chối đăng nhập/)
  assert.equal(r2.status, 'failed')
})

test('kiểm tra kết nối + lấy danh sách Team của công ty', async () => {
  const me = await api.test()
  assert.equal(me.user.name, 'Nguyễn Minh Phương')
  const teams = await api.listTeams()
  assert.deepEqual(teams[0], { id: 'team-1', code: 'CT01', name: 'WDC - Hoạt Huyết', status: 'ACTIVE' })
})

/* ------------------------------------------------------------------- Telegram */
const nowS = () => Math.floor(Date.now() / 1000)
const press = (data) => ({ update_id: 2, callback_query: { id: 'q1', data, message: { message_id: 77, date: nowS(), chat: { id: 42 } } } })
const say = (text, chat = 42) => ({ update_id: 1, message: { message_id: 1, date: nowS(), chat: { id: chat }, text } })
const lastText = () => tgSent.filter((x) => x.method === 'sendMessage' || x.method === 'editMessageText').at(-1).body.text

test('Telegram: Nhập Đơn/DSO → nhắn 2 số → Gửi → hỏi lại → Có thì mới gửi', async () => {
  const [r] = await company.createDrafts(17, '2026-10-04', { silent: true })
  await company.update(r.id, { metrics: { orders: '' } })
  store.get().settings.mock = false
  await bot.handleUpdate(press(`cs:${r.id}`))
  assert.match(lastText(), /Còn thiếu/)
  await bot.handleUpdate(press(`ce:${r.id}`))
  assert.match(lastText(), /Nhắn <b>số đơn<\/b>/)
  await bot.handleUpdate(say('mười hai'))
  assert.match(lastText(), /Chưa hiểu/)
  await bot.handleUpdate(say('12 3.500.000'))
  assert.equal(r.metrics.orders, 12)
  assert.equal(r.metrics.dso_after, 3500000)
  await bot.handleUpdate(press(`cs:${r.id}`))
  assert.match(lastText(), /Gửi báo cáo này lên hệ thống công ty\?/)
  assert.equal(co.calls.length, 0, 'chưa xác nhận thì chưa gọi công ty')
  await bot.handleUpdate(press(`ycs:${r.id}`))
  assert.match(lastText(), /✅ Đã gửi báo cáo/)
  assert.equal(r.status, 'sent')
  assert.equal(store.get().logs[0].source, 'Báo cáo công ty (Telegram)')
})

test('Telegram: người lạ nhắn số không sửa được báo cáo', async () => {
  const [r] = await company.createDrafts(17, '2026-10-04', { silent: true })
  await bot.handleUpdate(press(`ce:${r.id}`))
  const before = r.metrics.orders
  await bot.handleUpdate(say('12 3500000', 999))
  assert.equal(r.metrics.orders, before)
  assert.notEqual(r.metrics.dso_after, 3500000)
})

/* -------------------------------------------------------------- Tự động gửi */
// Số Facebook cố định (không phụ thuộc giờ chạy test, không gọi Facebook thật)
let realFb
function stubFb(m = { spend: 1520000, conversations: 15, leads: 12, results: 7, revenue: 2660000, impressions: 13680000, clicks: 1689 }, { fail } = {}) {
  realFb ||= { listObjects: fb.listObjects, rangeMetrics: fb.rangeMetrics }
  fb.listObjects = async () => { if (fail) throw new Error('Token Facebook đã hết hạn'); return [{ id: 'c1', level: 'campaign', accountId: 'mock_a', name: 'CT01 Hoạt huyết' }, { id: 'c2', level: 'campaign', accountId: 'mock_b', name: 'Khác' }] }
  fb.rangeMetrics = async () => ({ c1: m, c2: { spend: 999, conversations: 9, leads: 9, results: 9, revenue: 9, impressions: 9, clicks: 9 } })
}
function autoMode() {
  stubFb()
  store.get().company.mode = 'auto'
  store.get().settings.mock = false
}
const unstub = () => { if (realFb) Object.assign(fb, realFb) }
const posts = () => co.calls.filter((c) => c.method === 'POST' && c.path === '/reports')
const tgTexts = () => tgSent.filter((x) => x.method === 'sendMessage').map((x) => x.body.text)

test('Tự động gửi: đến mốc gửi luôn đủ 7 số (Đơn = kết quả, DSO = doanh thu), báo Telegram, ghi Nhật ký, chỉ 1 lần', async (t) => {
  t.after(unstub)
  autoMode()
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 + 1 })
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 + 2 })
  const [r] = store.get().companyReports
  assert.equal(r.status, 'sent')
  assert.equal(posts().length, 1)
  assert.deepEqual(posts()[0].body.metrics, { spend: 1520000, messages: 15, phones: 12, orders: 7, dso_after: 2660000, impressions: 13680000, clicks: 1689 })
  assert.equal(posts()[0].body.slot, 17)
  const texts = tgTexts()
  assert.equal(texts.length, 1)
  assert.match(texts[0], /✅ <b>Báo cáo công ty · 17h ngày 04\/10/)
  assert.match(texts[0], /Đã tự gửi lên công ty/)
  assert.equal(store.get().logs[0].source, 'Báo cáo công ty (tự động)')
  assert.equal(store.get().logs[0].ok, true)
})

test('Tự động gửi: có đơn mà doanh thu = 0 thì không gửi, chuyển "cần xem" kèm nút gửi tay', async (t) => {
  t.after(unstub)
  autoMode()
  stubFb({ spend: 100000, conversations: 2, leads: 1, results: 3, revenue: 0, impressions: 1000, clicks: 10 })
  await company.tick({ date: '2026-10-04', minutes: 12 * 60 })
  const [r] = store.get().companyReports
  assert.equal(r.status, 'review')
  assert.match(r.reasons[0], /doanh thu bằng 0/)
  assert.equal(posts().length, 0)
  assert.equal(co.calls.length, 0, 'không gọi hệ thống công ty')
  const msg = tgSent.find((x) => x.method === 'sendMessage')
  assert.match(msg.body.text, /Chưa tự gửi vì số trông bất thường/)
  assert.deepEqual(msg.body.reply_markup.inline_keyboard[0].map((b) => b.callback_data), [`ce:${r.id}`, `cs:${r.id}`])
  // Người dùng sửa DSO rồi gửi tay (Telegram có hỏi lại)
  await company.update(r.id, { metrics: { dso_after: 900000 } })
  await bot.handleUpdate(press(`cs:${r.id}`))
  await bot.handleUpdate(press(`ycs:${r.id}`))
  assert.equal(r.status, 'sent')
  assert.equal(posts()[0].body.metrics.dso_after, 900000)
})

test('Tự động gửi: Team không khớp chiến dịch nào thì không gửi', async (t) => {
  t.after(unstub)
  autoMode()
  store.get().company.teams = [{ ...TEAM, accountIds: ['khong_co'] }]
  await company.tick({ date: '2026-10-04', minutes: 22 * 60 })
  assert.equal(store.get().companyReports[0].status, 'review')
  assert.equal(co.calls.length, 0)
})

test('Tự động gửi: không lấy được số Facebook thì báo Telegram, không tạo/gửi gì', async (t) => {
  t.after(unstub)
  autoMode()
  stubFb(undefined, { fail: true })
  await company.tick({ date: '2026-10-04', minutes: 9 * 60 })
  assert.equal(store.get().companyReports.length, 0)
  assert.equal(co.calls.length, 0)
  assert.match(tgTexts()[0], /không lấy được số Facebook/)
})

test('Tự động gửi: hệ thống công ty lỗi 5xx thì thử lại sau 10 phút, tối đa 3 lần rồi báo lỗi kèm nút', async (t) => {
  t.after(unstub)
  autoMode()
  const realFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = realFetch })
  globalThis.fetch = async (url, init = {}) => {
    if (new URL(String(url)).pathname === '/api/reports' && init.method === 'POST') { co.calls.push({ method: 'POST', path: '/reports' }); return json({ error: 'Lỗi máy chủ' }, 503) }
    return realFetch(url, init)
  }
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 })
  const [r] = store.get().companyReports
  assert.equal(r.status, 'retry')
  assert.equal(r.attempts, 1)
  assert.equal(tgTexts().length, 0, 'lần đầu lỗi chưa làm phiền')
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 + 1 }) // chưa đến giờ thử lại
  assert.equal(posts().length, 1)
  for (const n of [2, 3]) {
    r.nextTryAt = new Date(Date.now() - 1000).toISOString()
    await company.tick({ date: '2026-10-04', minutes: 17 * 60 + 10 * n })
    assert.equal(r.attempts, n)
  }
  assert.equal(r.status, 'failed')
  assert.equal(posts().length, 3)
  const texts = tgTexts()
  assert.equal(texts.length, 1)
  assert.match(texts[0], /Không tự gửi được \(đã thử 3 lần\)/)
  await company.tick({ date: '2026-10-04', minutes: 18 * 60 })
  assert.equal(posts().length, 3, 'thất bại rồi thì không thử nữa')
})

test('Tự động gửi: sai mật khẩu thì báo ngay, không thử lại', async (t) => {
  t.after(unstub)
  autoMode()
  store.get().company.password = 'sai'
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 })
  const [r] = store.get().companyReports
  assert.equal(r.status, 'failed')
  assert.equal(r.attempts, 1)
  assert.equal(co.logins, 1)
  assert.match(tgTexts()[0], /Không tự gửi được: .*từ chối đăng nhập/)
})

test('Tự động gửi: mốc đã có báo cáo trên công ty thì không gửi đè', async (t) => {
  t.after(unstub)
  autoMode()
  co.reports.push({ id: 'old', team_id: 'team-1', date: '2026-10-04', slot: 17, user_id: 'u1', status: 'SUBMITTED' })
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 })
  assert.equal(store.get().companyReports[0].status, 'exists')
  assert.equal(posts().length, 0)
  assert.match(tgTexts()[0], /không gửi đè/)
})

const yesterday9h = (extra = {}) => ({ id: 'rep-9h', team_id: 'team-1', date: '2026-10-04', slot: 9, user_id: 'u1', status: 'SUBMITTED', revision: 1, locked: false, metrics: { spend: 1, messages: 1, phones: 1, orders: 1, dso_after: 1, impressions: 1, clicks: 1 }, ...extra })

test('Tự động gửi mốc 9h: cập nhật vào báo cáo 9h ngày hôm qua đã có, kèm lần sửa + lý do, không tạo bản ghi ngày hôm nay', async (t) => {
  t.after(unstub)
  autoMode()
  co.reports.push(yesterday9h())
  await company.tick({ date: '2026-10-05', minutes: 9 * 60 + 1 })
  const [r] = store.get().companyReports
  assert.equal(r.date, '2026-10-04')
  assert.equal(r.status, 'sent')
  assert.equal(r.sentAs, 'update')
  assert.equal(posts().length, 1)
  assert.deepEqual(co.updates, [{ team_id: 'team-1', date: '2026-10-04', slot: 9, metrics: { spend: 1520000, messages: 15, phones: 12, orders: 7, dso_after: 2660000, impressions: 13680000, clicks: 1689 }, notes: '', issue: '', resolution: '', revision: 1, reason: 'Chốt số liệu cả ngày 04/10' }])
  assert.equal(co.reports.length, 1, 'không tạo báo cáo ngày 05/10')
  assert.equal(co.reports[0].metrics.orders, 7)
  assert.equal(r.remote.revision, 2)
  assert.match(tgTexts()[0], /9h ngày 04\/10<\/b> · chốt cả ngày, cập nhật sáng 05\/10/)
  assert.match(tgTexts()[0], /Đã tự cập nhật vào báo cáo 9h ngày 04\/10 trên công ty/)
  assert.match(store.get().logs[0].detail, /Đã cập nhật báo cáo 9h ngày 04\/10 .*Chốt số liệu cả ngày 04\/10/)
})

test('Tự động gửi mốc 9h: chưa có bản ghi 9h hôm qua thì tạo mới với ngày hôm qua', async (t) => {
  t.after(unstub)
  autoMode()
  await company.tick({ date: '2026-10-05', minutes: 9 * 60 + 1 })
  const [r] = store.get().companyReports
  assert.equal(r.status, 'sent')
  assert.equal(r.sentAs, 'create')
  assert.equal(posts().length, 1)
  assert.equal(posts()[0].body.date, '2026-10-04')
  assert.equal(posts()[0].body.reason, undefined)
  assert.equal(co.updates.length, 0)
  assert.match(tgTexts()[0], /Đã tự gửi lên công ty/)
})

test('Tự động gửi mốc 9h: bản ghi 9h hôm qua đã khoá thì không đụng tới, chỉ báo', async (t) => {
  t.after(unstub)
  autoMode()
  co.reports.push(yesterday9h({ locked: true }))
  await company.tick({ date: '2026-10-05', minutes: 9 * 60 + 1 })
  const [r] = store.get().companyReports
  assert.equal(r.status, 'exists')
  assert.equal(posts().length, 0)
  assert.equal(co.reports[0].metrics.orders, 1)
  assert.match(tgTexts()[0], /đã khoá nên tool không cập nhật được/)
  assert.equal(company.updatable(r), false)
})

test('Duyệt trước mốc 9h: đồng bộ không chặn bản chờ gửi; bấm Gửi thì cập nhật vào bản ghi 9h hôm qua', async (t) => {
  t.after(unstub)
  stubFb()
  store.get().settings.mock = false
  const today = new Date().toISOString().slice(0, 10)
  const [r] = await company.createDrafts(9, today, { silent: true })
  co.reports.push(yesterday9h({ date: r.date }))
  assert.equal(await company.sync(), 1)
  assert.equal(r.status, 'pending', 'mốc 9h: bản ghi đã có là chỗ sẽ cập nhật vào')
  await company.send(r.id)
  assert.equal(r.sentAs, 'update')
  assert.equal(co.updates.length, 1)
  assert.equal(co.updates[0].reason, S.closeReason(r.date))
})

test('Tự động gửi: đang Dùng thử (dữ liệu giả) thì không gửi, chỉ báo', async (t) => {
  t.after(unstub)
  autoMode()
  store.get().settings.mock = true
  await company.tick({ date: '2026-10-04', minutes: 17 * 60 })
  assert.equal(store.get().companyReports[0].status, 'pending')
  assert.equal(co.calls.length, 0)
  assert.match(tgTexts()[0], /dữ liệu giả/)
})

/* ------------------------------------------- Cập nhật báo cáo đã có + đồng bộ + làm sớm */
test('làm sớm n phút: tạo báo cáo mốc 12h lúc 11:50, vẫn ghi mốc 12h; chưa đến giờ thì chưa làm', async () => {
  store.get().company.leadMin = 10
  await company.tick({ date: '2026-10-04', minutes: 11 * 60 + 49 })
  assert.equal(store.get().companyReports.length, 0)
  await company.tick({ date: '2026-10-04', minutes: 11 * 60 + 50 })
  const [r] = store.get().companyReports
  assert.equal(r.slot, 12)
  assert.equal(r.date, '2026-10-04')
  await company.tick({ date: '2026-10-04', minutes: 12 * 60 })
  assert.equal(store.get().companyReports.length, 1, 'không làm lại lúc 12h')
  assert.equal(S.fireMinute(9, 30), 8 * 60 + 30)
  assert.equal(S.fireMinute(9, 999), 8 * 60, 'tối đa 60 phút')
  assert.match(S.validateCompanyConfig({ leadMin: 61 }).errors.leadMin, /0 đến 60/)
  assert.equal(S.validateCompanyConfig({ leadMin: '15' }).value.leadMin, 15)
})

test('gửi xong lưu trạng thái công ty; sửa số rồi Cập nhật: gửi kèm lần sửa + lý do, thiếu lý do thì chặn', async () => {
  const r = await readyDraft()
  await company.send(r.id)
  assert.equal(r.remote.revision, 1)
  assert.equal(r.remote.locked, false)
  assert.deepEqual(S.diffRemote(r), [])
  await company.update(r.id, { metrics: { orders: 6 } })
  assert.deepEqual(S.diffRemote(r), ['orders'])
  await assert.rejects(company.updateRemote(r.id, { reason: '  ' }), /lý do/)
  await company.updateRemote(r.id, { reason: 'Chốt thêm 2 đơn' })
  assert.equal(co.updates.length, 1)
  assert.equal(co.updates[0].revision, 1)
  assert.equal(co.updates[0].reason, 'Chốt thêm 2 đơn')
  assert.equal(co.updates[0].metrics.orders, 6)
  assert.equal(r.remote.revision, 2)
  assert.equal(r.remote.locked, true, 'công ty khoá lại sau một lần sửa')
  assert.match(store.get().logs[0].detail, /Đã cập nhật báo cáo 17h.*Chốt thêm 2 đơn/)
  // đã khoá → không sửa nữa, không gọi công ty
  await assert.rejects(company.update(r.id, { metrics: { orders: 7 } }), /đã khoá/)
  const before = co.calls.length
  await assert.rejects(company.updateRemote(r.id, { reason: 'x' }), /đã khoá/)
  assert.ok(co.calls.slice(before).every((c) => c.method === 'GET'))
})

test('cập nhật: có người vừa sửa trên web (lần sửa khác) thì tải số mới, không ghi đè', async () => {
  const r = await readyDraft()
  await company.send(r.id)
  Object.assign(co.reports[0], { revision: 2, metrics: { ...co.reports[0].metrics, orders: 9 } })
  await company.update(r.id, { metrics: { orders: 5 } })
  await assert.rejects(company.updateRemote(r.id, { reason: 'Sửa đơn' }), /vừa được sửa ở nơi khác \(lần sửa 2\)/)
  assert.equal(co.updates.length, 0)
  assert.equal(r.remote.revision, 2)
  assert.equal(r.remote.metrics.orders, 9)
  await company.updateRemote(r.id, { reason: 'Sửa đơn' })
  assert.equal(co.updates[0].revision, 2)
})

test('đồng bộ: mốc đã nộp tay trên web → "Công ty đã có" kèm số trên công ty; Chỉ xem / Dùng thử thì không cập nhật', async () => {
  const r = await readyDraft()
  co.reports.push({ id: 'web-1', team_id: 'team-1', date: '2026-10-04', slot: 17, user_id: 'u1', status: 'LATE', revision: 1, locked: false, metrics: { spend: 1, messages: 1, phones: 1, orders: 1, dso_after: 1, impressions: 1, clicks: 1 } })
  r.date = new Date().toISOString().slice(0, 10); co.reports[0].date = r.date
  assert.equal(await company.sync(), 1)
  assert.equal(r.status, 'exists')
  assert.equal(r.remote.status, 'LATE')
  assert.equal(r.remote.metrics.orders, 1)
  assert.ok(S.diffRemote(r).includes('spend'))
  store.get().company.mode = 'preview'
  await assert.rejects(company.updateRemote(r.id, { reason: 'x' }), /Chỉ xem/)
  store.get().company.mode = 'approve'
  store.get().settings.mock = true
  await assert.rejects(company.updateRemote(r.id, { reason: 'x' }), /dữ liệu giả/)
  assert.equal(await company.sync(), 0, 'Dùng thử không gọi công ty')
  assert.equal(co.updates.length, 0)
})

test('Telegram: Cập nhật lên công ty → nhắn lý do → hỏi lại kèm số thay đổi → Có thì mới cập nhật', async () => {
  const r = await readyDraft()
  await company.send(r.id)
  await company.update(r.id, { metrics: { dso_after: 3000000 } })
  await bot.handleUpdate(press(`cu:${r.id}`))
  assert.ok(tgSent.some((x) => /lý do cập nhật/.test(x.body.text || '')))
  await bot.handleUpdate(say('DSO chốt lại'))
  assert.match(lastText(), /DSO sau VAT: 2\.500\.000 → <b>3\.000\.000<\/b>/)
  assert.match(lastText(), /Lý do: DSO chốt lại/)
  assert.equal(co.updates.length, 0, 'chưa xác nhận thì chưa cập nhật')
  await bot.handleUpdate(press(`ycu:${r.id}`))
  assert.match(lastText(), /✅ Đã cập nhật báo cáo .*lần sửa 2/)
  assert.equal(co.updates[0].reason, 'DSO chốt lại')
  assert.equal(store.get().logs[0].source, 'Báo cáo công ty (Telegram)')
  await bot.handleUpdate(press(`cu:${r.id}`))
  assert.match(lastText(), /đã khoá/)
})
