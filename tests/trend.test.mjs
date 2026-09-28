// Xu hướng theo ngày (fb.dailyTrend) và báo cáo tuần (engine.sendWeeklyReport). Facebook và Telegram được thay bằng hàm giả.
import { test, beforeEach, mock } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateSettings } from '../shared/validate.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-trend-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')

fb._net.retryWaitMs = [0, 0]
let fbCalls, sent, fbReply
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
globalThis.fetch = async (url, init = {}) => {
  const u = new URL(String(url))
  if (u.hostname === 'api.telegram.org') { sent.push(JSON.parse(init.body).text); return json({ ok: true, result: { message_id: 1 } }) }
  fbCalls.push(u)
  return fbReply(u)
}

beforeEach(() => {
  const d = store.get()
  d.logs.length = 0
  d.state.lastReport = ''; d.state.lastWeekly = ''
  Object.assign(d.settings, { mock: true, dryRun: false, accessToken: 'EAAtoken-1234567890', adAccountIds: ['111'], adAccountId: '111', telegramToken: '1:x', telegramChatId: '42',
    reportTime: '08:00', weeklyReport: true, timezone: 'Asia/Ho_Chi_Minh', resultAction: 'purchase' })
  fbCalls = []; sent = []
  fbReply = () => json({ data: [] })
  fb.resetCache(); fb.resetMock()
})

test('xu hướng (Facebook thật): ngày không chạy được điền 0, giữ số liệu 1 giờ', async () => {
  store.get().settings.mock = false
  fbReply = () => json({ data: [
    { date_start: '2026-09-01', spend: '100000', impressions: '1000', clicks: '10', actions: [{ action_type: 'purchase', value: '2' }] },
    { date_start: '2026-09-03', spend: '50000', impressions: '500', clicks: '5' },
  ] })
  const r = await fb.dailyTrend('555', '2026-09-01', '2026-09-04')
  assert.deepEqual(r.days.map((d) => [d.date, d.spend, d.results]), [['2026-09-01', 100000, 2], ['2026-09-02', 0, 0], ['2026-09-03', 50000, 0], ['2026-09-04', 0, 0]])
  assert.equal(r.days[0].cpa, 50000)
  const q = fbCalls[0]
  assert.match(q.pathname, /\/555\/insights$/)
  assert.equal(q.searchParams.get('time_increment'), '1')
  assert.deepEqual(JSON.parse(q.searchParams.get('time_range')), { since: '2026-09-01', until: '2026-09-04' })

  await fb.dailyTrend('555', '2026-09-01', '2026-09-04')
  await fb.dailyTrend('555', '2026-09-01', '2026-09-04', true) // bấm Làm mới ngay sau khi tải: vẫn dùng lại
  assert.equal(fbCalls.length, 1)
})

test('xu hướng: bị Facebook giới hạn thì dùng số cũ (đánh dấu stale), chưa có số cũ thì báo lỗi', async () => {
  store.get().settings.mock = false
  fbReply = () => json({ data: [{ date_start: '2026-09-01', spend: '1000' }] })
  await fb.dailyTrend('555', '2026-09-01', '2026-09-02')
  const realNow = Date.now
  Date.now = () => realNow() + 2 * 3600e3 // quá 1 giờ: phải hỏi lại
  try {
    fbReply = () => json({ error: { code: 17, message: 'User request limit reached' } }, 400)
    const r = await fb.dailyTrend('555', '2026-09-01', '2026-09-02')
    assert.equal(r.stale, true)
    assert.equal(r.days[0].spend, 1000)
    await assert.rejects(fb.dailyTrend('666', '2026-09-01', '2026-09-02'), /giới hạn số lần gọi/)
  } finally { Date.now = realNow }
})

test('xu hướng (dữ liệu mẫu): ổn định giữa các lần mở, camp không có thì lỗi 404', async () => {
  const a = await fb.dailyTrend('mock_1', '2026-09-01', '2026-09-30')
  fb.resetCache()
  const b = await fb.dailyTrend('mock_1', '2026-09-01', '2026-09-30')
  assert.equal(a.days.length, 30)
  assert.deepEqual(a.days, b.days)
  await assert.rejects(fb.dailyTrend('nope', '2026-09-01', '2026-09-02'), (e) => e.status === 404)
})

test('tuần trước luôn là thứ Hai → Chủ nhật đã qua', () => {
  assert.deepEqual(engine.lastWeek({ date: '2026-09-28', day: 1 }), { since: '2026-09-21', until: '2026-09-27', prevSince: '2026-09-14', prevUntil: '2026-09-20' }) // thứ Hai
  assert.deepEqual(engine.lastWeek({ date: '2026-10-01', day: 4 }).since, '2026-09-21') // thứ Năm: vẫn là tuần 21–27
  assert.deepEqual(engine.lastWeek({ date: '2026-10-04', day: 0 }).until, '2026-09-27') // Chủ nhật: tuần này chưa hết
})

test('xếp hạng camp: tốt nhất theo CPA thấp, cần xem lại là chi mà không có kết quả rồi tới CPA cao', () => {
  const r = (id, spend, results) => ({ o: { id }, m: { spend, results, cpa: results ? spend / results : null } })
  const { best, worst } = engine.rankCamps([r('a', 100, 10), r('b', 100, 2), r('c', 300, 0), r('d', 100, 5), r('e', 0, 0), r('f', 100, 1), r('g', 50, 0)])
  assert.deepEqual(best.map((x) => x.o.id), ['a', 'd', 'b'])
  assert.deepEqual(worst.map((x) => x.o.id), ['c', 'g', 'f'])
})

test('báo cáo tuần: so với tuần trước, có camp tốt/tệ và số lần tool tự thao tác', async () => {
  const d = store.get()
  const at = (iso) => `${iso}T05:00:00.000Z`
  d.logs.push(
    { id: 'l1', ts: at('2026-09-22'), kind: 'rule', ok: true, mode: 'mock', action: { type: 'off' } },
    { id: 'l2', ts: at('2026-09-23'), kind: 'schedule', ok: true, mode: 'mock', action: { type: 'budget' } },
    { id: 'l3', ts: at('2026-09-23'), kind: 'rule', ok: true, dry: true, mode: 'mock', action: { type: 'off' } }, // chạy thử: không tính
    { id: 'l4', ts: at('2026-09-23'), kind: 'manual', ok: true, mode: 'mock', action: { type: 'off' } },          // làm tay: không tính
    { id: 'l5', ts: at('2026-09-29'), kind: 'rule', ok: true, mode: 'mock', action: { type: 'on' } },            // tuần này: không tính
  )
  await engine.sendWeeklyReport({ date: '2026-09-28', day: 1, minutes: 480 })
  assert.equal(sent.length, 1)
  const t = sent[0]
  assert.match(t, /Báo cáo tuần 21\/09 – 27\/09/)
  assert.match(t, /Tài khoản mẫu A/)
  assert.match(t, /Tốt nhất/)
  assert.match(t, /Tool đã tự thao tác 2 lần: bật 0, tắt 1, đổi ngân sách 1/)
})

test('sáng thứ Hai gửi cả báo cáo ngày và báo cáo tuần, mỗi loại 1 lần; tắt báo cáo tuần thì chỉ gửi báo cáo ngày', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-09-28T01:02:00Z') }) // 08:02 thứ Hai giờ Việt Nam
  await engine.tickReport()
  assert.equal(sent.length, 2)
  assert.match(sent[1], /Báo cáo tuần/)
  await engine.tickReport()
  assert.equal(sent.length, 2, 'không gửi lại trong cùng ngày')

  store.get().state.lastReport = ''; store.get().state.lastWeekly = ''; sent = []
  store.get().settings.weeklyReport = false
  await engine.tickReport()
  assert.equal(sent.length, 1)
  assert.doesNotMatch(sent[0], /Báo cáo tuần/)
})

test('ngày khác thứ Hai không gửi báo cáo tuần', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-09-29T01:02:00Z') }) // thứ Ba
  await engine.tickReport()
  assert.equal(sent.length, 1)
  assert.doesNotMatch(sent[0], /Báo cáo tuần/)
})

test('validate: weeklyReport là bật/tắt', () => {
  assert.equal(validateSettings({ weeklyReport: false }).ok, true)
  assert.equal(validateSettings({ weeklyReport: false }).value.weeklyReport, false)
})
