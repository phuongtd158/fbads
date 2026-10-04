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
    if (body.password !== 'dung-mat-khau') return json({ error: 'Email hoặc mật khẩu không đúng.' }, 401)
    co.session = `sess${co.logins}`
    return json({ user: { id: 'u1', name: 'Nguyễn Minh Phương', role: 'MKT', must_change: 0 }, csrf: `csrf${co.logins}` }, 200, { 'set-cookie': `wellday_session=${co.session}; Path=/; HttpOnly; Secure` })
  }
  const h = init.headers || {}
  if (h.Cookie !== `wellday_session=${co.session}` || h['X-CSRF-Token'] !== `csrf${co.logins}` || co.expire) { co.expire = false; return json({ error: 'Phiên đăng nhập đã hết hạn.' }, 401) }
  if (p === '/teams') return json([{ id: 'team-1', code: 'CT01', name: 'WDC - Hoạt Huyết', status: 'ACTIVE' }, { id: 'team-2', code: 'CT02', name: 'Khác', status: 'ACTIVE' }])
  if (p === '/reports' && (init.method || 'GET') === 'GET') return json(co.reports.filter((r) => r.team_id === u.searchParams.get('team_id') && r.date === u.searchParams.get('from')))
  if (p === '/reports' && init.method === 'POST') {
    if (co.failPost) return json({ error: 'Dữ liệu không hợp lệ.' }, 400)
    const r = { id: `rep-${co.reports.length + 1}`, user_id: 'u1', status: 'SUBMITTED', ...body }
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
  Object.assign(d.company, { enabled: true, mode: 'approve', slots: [9, 12, 17, 22], baseUrl: BASE, email: 'mkt@congty.vn', password: 'dung-mat-khau', teams: [TEAM] })
  fb.resetMock(); fb.resetCache(); api.reset(); bot.reset()
  co = { calls: [], logins: 0, session: '', reports: [], expire: false, failPost: false }
  tgSent = []
})

/* ------------------------------------------------------------ luật dùng chung */
test('mốc 9h báo số hôm qua, các mốc khác báo hôm nay', () => {
  assert.equal(S.reportDate(9, '2026-10-01'), '2026-09-30')
  assert.equal(S.reportDate(17, '2026-10-01'), '2026-10-01')
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

test('cộng số: tin nhắn = cuộc trò chuyện, SĐT = khách hàng tiềm năng, làm tròn', () => {
  const camps = [{ id: '1' }, { id: '2' }, { id: '3' }]
  const data = { 1: { spend: 100.4, conversations: 3, leads: 2, impressions: 1000, clicks: 10 }, 2: { spend: 200.4, conversations: 1, leads: 0, impressions: 500, clicks: 5 } }
  assert.deepEqual(S.sumMetrics(camps, data), { spend: 301, messages: 4, phones: 2, impressions: 1500, clicks: 15 })
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
  await company.tick({ date: '2026-10-04', minutes: 9 * 60 + 3 })
  await company.tick({ date: '2026-10-04', minutes: 9 * 60 + 5 }) // vòng sau trong cùng mốc: không tạo lại
  const list = store.get().companyReports
  assert.equal(list.length, 1)
  const r = list[0]
  assert.equal(r.date, '2026-10-03')
  assert.equal(r.slot, 9)
  assert.equal(r.status, 'pending')
  assert.equal(r.metrics.orders, null)
  assert.ok(r.campaigns.length > 0 && r.campaigns.length <= 4, 'chỉ các camp của tài khoản mẫu A')
  assert.ok(r.metrics.spend > 0)
  const msgs = tgSent.filter((x) => x.method === 'sendMessage')
  assert.equal(msgs.length, 1)
  assert.match(msgs[0].body.text, /Báo cáo công ty · 9h ngày 03\/10/)
  assert.match(msgs[0].body.text, /Đơn hàng: <i>chưa nhập<\/i>/)
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

test('làm mới mốc: giữ Đơn/DSO và ghi chú đã nhập, không đụng bản đã gửi', async () => {
  const [r] = await company.createDrafts(12, '2026-10-04', { silent: true })
  await company.update(r.id, { metrics: { orders: 5, dso_after: 1000000 }, notes: 'ok' })
  const [again] = await company.createDrafts(12, '2026-10-04', { silent: true })
  assert.equal(again.id, r.id)
  assert.equal(again.metrics.orders, 5)
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
  assert.doesNotMatch(JSON.stringify(store.get().logs), /dung-mat-khau/, 'mật khẩu không bao giờ vào nhật ký')
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
  await bot.handleUpdate(say('12 3500000', 999))
  assert.equal(r.metrics.orders, null)
})
