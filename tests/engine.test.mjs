// Kiểm thử engine trên dữ liệu giả (mock). DATA_DIR trỏ vào thư mục tạm nên không bao giờ đụng data.json thật.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { undoBlocker } from '../shared/validate.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')

const S = () => store.get()
const camp = async (id) => (await fb.listObjects(true)).find((o) => o.id === id)

beforeEach(() => {
  const d = S()
  d.logs.length = 0; d.rules.length = 0; d.schedules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true, dailyChangeCapPct: 30, killSwitchEnabled: false, dailySpendLimit: 0 })
  fb.resetMock(); fb.resetCache()
})

/* --------------------------------------------------------------- plan() */
test('plan: bật/tắt đã đúng trạng thái thì bỏ qua', () => {
  assert.equal(engine.plan({ effective: 'ACTIVE' }, { type: 'on' }).kind, 'noop')
  assert.equal(engine.plan({ effective: 'ACTIVE' }, { type: 'off' }).kind, 'do')
})

test('plan: ngân sách theo %, có trần/sàn', () => {
  const o = { id: 'a', dailyBudget: 100000, effective: 'ACTIVE' }
  assert.equal(engine.plan(o, { type: 'budget', mode: 'percent', value: 20 }, { kind: 'schedule' }).next, 120000)
  assert.equal(engine.plan(o, { type: 'budget', mode: 'percent', value: 50, max: 130000 }, { kind: 'schedule' }).next, 130000)
  assert.equal(engine.plan(o, { type: 'budget', mode: 'percent', value: -50, min: 70000 }, { kind: 'schedule' }).next, 70000)
})

test('plan: camp CBO không đổi được ngân sách', () => {
  assert.equal(engine.plan({ id: 'c', dailyBudget: null }, { type: 'budget', mode: 'percent', value: 10 }).kind, 'error')
})

test('plan: rule bỏ qua camp đang học, lịch thì không', () => {
  const o = { id: 'a', dailyBudget: 100000, effective: 'ACTIVE', learning: true }
  const act = { type: 'budget', mode: 'percent', value: 20 }
  assert.equal(engine.plan(o, act, { kind: 'rule' }).code, 'learning')
  assert.equal(engine.plan(o, act, { kind: 'schedule' }).kind, 'do')
  S().settings.skipLearning = false
  assert.equal(engine.plan(o, act, { kind: 'rule' }).kind, 'do')
})

test('plan: rule bị giới hạn thay đổi mỗi ngày', () => {
  const o = { id: 'a', dailyBudget: 100000, effective: 'ACTIVE' }
  const p = engine.plan(o, { type: 'budget', mode: 'percent', value: 50 }, { kind: 'rule' })
  assert.equal(p.next, 130000)
  assert.equal(p.capped, true)
  // lịch không bị giới hạn (ý định rõ ràng của người dùng)
  assert.equal(engine.plan(o, { type: 'budget', mode: 'percent', value: 50 }, { kind: 'schedule' }).next, 150000)
})

test('plan: rule chỉ thông báo', () => {
  const p = engine.plan({ id: 'a', effective: 'ACTIVE' }, { type: 'notify', message: 'CPA cao' }, { kind: 'rule' })
  assert.equal(p.kind, 'do'); assert.equal(p.notify, true)
})

/* ----------------------------------------------- act(): tích luỹ trong ngày */
test('act: rule tăng ngân sách nhiều lần trong ngày bị chặn ở giới hạn 30%', async () => {
  const rule = { kind: 'rule', refId: 'r1', refName: 'Tăng' }
  const inc = { type: 'budget', mode: 'percent', value: 20 }
  await engine.act(await camp('mock_1'), inc, 'Rule: test', rule)
  assert.equal((await camp('mock_1')).dailyBudget, 600000) // +20%
  await engine.act(await camp('mock_1'), inc, 'Rule: test', rule)
  assert.equal((await camp('mock_1')).dailyBudget, 650000) // chạm trần +30% so với gốc 500.000
  await engine.act(await camp('mock_1'), inc, 'Rule: test', rule)
  assert.equal((await camp('mock_1')).dailyBudget, 650000) // không tăng nữa
  const skip = S().logs.filter((l) => l.skipped)
  assert.equal(skip.length, 1)
  assert.match(skip[0].detail, /giới hạn/)
  await engine.act(await camp('mock_1'), inc, 'Rule: test', rule)
  assert.equal(S().logs.filter((l) => l.skipped).length, 1) // cùng lý do chỉ ghi 1 lần/ngày
})

test('act: nhật ký ghi tài khoản quảng cáo của camp (để lịch sử còn đúng khi camp bị xoá)', async () => {
  await engine.act(await camp('mock_1'), { type: 'off' }, 'Thủ công', { kind: 'manual' })
  await engine.act(await camp('mock_5'), { type: 'off' }, 'Thủ công', { kind: 'manual' })
  const byId = Object.fromEntries(S().logs.map((l) => [l.target.id, l.target]))
  assert.deepEqual([byId.mock_1.accountId, byId.mock_1.accountName], ['mock_a', 'Tài khoản mẫu A'])
  assert.deepEqual([byId.mock_5.accountId, byId.mock_5.accountName], ['mock_b', 'Tài khoản mẫu B'])
})

test('act: rule bỏ qua camp đang học và ghi nhận', async () => {
  const before = (await camp('mock_5')).dailyBudget
  assert.equal((await camp('mock_5')).learning, true)
  await engine.act(await camp('mock_5'), { type: 'budget', mode: 'percent', value: 20 }, 'Rule: test', { kind: 'rule', refId: 'r1' })
  assert.equal((await camp('mock_5')).dailyBudget, before)
  assert.ok(S().logs.some((l) => l.skipped && /học/.test(l.detail)))
})

/* --------------------------------------------------- Khoảng thời gian */
test('rule: đánh giá theo khoảng thời gian khác nhau cho kết quả khác nhau', async () => {
  const objs = await fb.listObjects(true)
  const maps = { today: await fb.rangeMetrics('today'), last_7d: await fb.rangeMetrics('last_7d') }
  const rule = { id: 'r1', name: 'x', metric: 'spend', op: '>', value: 1500000, minSpend: 0, action: 'pause', allActive: true, level: 'campaign' }
  const today = engine.evaluateRule({ ...rule, range: 'today' }, objs, (r) => maps[r])
  const week = engine.evaluateRule({ ...rule, range: 'last_7d' }, objs, (r) => maps[r])
  assert.equal(today.filter((d) => d.hit).length, 0) // hôm nay chi chưa tới 1,5 triệu
  assert.ok(week.filter((d) => d.hit).length >= 1) // 7 ngày thì có camp vượt
  assert.ok(week.every((d) => d.range === 'last_7d'))
})

test('rule: chi tiêu tối thiểu, khung giờ và thời gian nghỉ', async () => {
  const objs = await fb.listObjects(true)
  const map = await fb.rangeMetrics('today')
  const rule = { id: 'r1', metric: 'spend', op: '>', value: 0, minSpend: 99999999, action: 'pause', allActive: true, level: 'campaign', range: 'today' }
  assert.ok(engine.evaluateRule(rule, objs, () => map).every((d) => d.code === 'minspend'))
  const r2 = { ...rule, minSpend: 0, value: -1, op: '>', cooldownHours: 24 }
  S().state.lastRule['r1:mock_1'] = Date.now()
  const d = engine.evaluateRule(r2, objs, () => map).find((x) => x.obj.id === 'mock_1')
  assert.equal(d.code, 'cooldown')
})

/* --------------------------------------------------------- Xem trước */
test('xem trước: không thay đổi camp và không đặt thời gian nghỉ', async () => {
  const rule = { id: 'r9', name: 'Tắt khi chi > 0', metric: 'spend', op: '>', value: 1, minSpend: 0, action: 'pause', cooldownHours: 24, allActive: true, level: 'campaign', range: 'today' }
  const before = (await camp('mock_1')).effective
  const pv = await engine.previewRule(rule)
  assert.ok(pv.items.length > 0)
  assert.ok(pv.counts.match >= 1)
  assert.equal((await camp('mock_1')).effective, before)
  assert.deepEqual(S().state.lastRule, {})
  assert.equal(S().logs.length, 0)
})

/* -------------------------------------------------- Chỉ thông báo */
test('runRules: rule chỉ thông báo ghi nhật ký mà không đổi camp', async () => {
  S().rules.push({ id: 'n1', name: 'Cảnh báo', enabled: true, metric: 'spend', op: '>', value: 1, minSpend: 0, action: 'notify', cooldownHours: 24, allActive: true, level: 'campaign', range: 'today' })
  const before = (await fb.listObjects(true)).filter((o) => o.effective === 'ACTIVE').length
  await engine.runRules()
  const logs = S().logs.filter((l) => l.action && l.action.type === 'notify')
  assert.ok(logs.length >= 1)
  assert.ok(logs.every((l) => l.ok && !l.after))
  assert.equal((await fb.listObjects(true)).filter((o) => o.effective === 'ACTIVE').length, before)
  const n = logs.length
  await engine.runRules() // trong thời gian nghỉ → không gửi lại
  assert.equal(S().logs.filter((l) => l.action && l.action.type === 'notify').length, n)
})

/* ------------------------------------------------------ Dừng khẩn */
test('dừng khẩn: tắt mọi camp khi tổng chi tiêu vượt mức, mỗi ngày một lần', async () => {
  Object.assign(S().settings, { killSwitchEnabled: true, dailySpendLimit: 1 })
  const objs = await fb.listObjects(true)
  assert.ok(objs.some((o) => o.effective === 'ACTIVE'))
  assert.equal(await engine.checkKillSwitch(objs), true)
  assert.equal((await fb.listObjects(true)).filter((o) => o.level === 'campaign' && o.effective === 'ACTIVE').length, 0)
  assert.ok(S().logs.some((l) => l.source === 'Dừng khẩn'))
  assert.equal(await engine.checkKillSwitch(await fb.listObjects(true)), false) // đã kích hoạt hôm nay
})

test('dừng khẩn: không kích hoạt khi chưa bật hoặc chưa vượt mức', async () => {
  const objs = await fb.listObjects(true)
  assert.equal(await engine.checkKillSwitch(objs), false)
  Object.assign(S().settings, { killSwitchEnabled: true, dailySpendLimit: 1e12 })
  assert.equal(await engine.checkKillSwitch(objs), false)
})

/* -------------------------------------------------------- Hoàn tác */
test('undoBlocker: các trường hợp không hoàn tác được', () => {
  const ok = { id: 'x', ok: true, kind: 'rule', ts: new Date().toISOString(), action: { type: 'off' }, target: { id: 'a' }, before: { status: 'ACTIVE' }, after: { status: 'PAUSED' } }
  assert.equal(undoBlocker(ok), '')
  assert.ok(undoBlocker({ ...ok, ok: false }))
  assert.ok(undoBlocker({ ...ok, dry: true }))
  assert.ok(undoBlocker({ ...ok, undone: { at: 'x' } }))
  assert.ok(undoBlocker({ ...ok, kind: 'undo' }))
  assert.ok(undoBlocker({ ...ok, skipped: true }))
  assert.ok(undoBlocker({ ...ok, action: { type: 'notify' } }))
  assert.ok(undoBlocker({ ...ok, before: undefined }))
  assert.ok(undoBlocker({ ...ok, ts: new Date(Date.now() - 5 * 864e5).toISOString() }))
})

test('hoàn tác: tắt camp rồi hoàn tác thì bật lại; không hoàn tác hai lần', async () => {
  const o = await camp('mock_1')
  assert.equal(o.effective, 'ACTIVE')
  await engine.act(o, { type: 'off' }, 'Rule: test', { kind: 'rule', refId: 'r1', refName: 'T' })
  assert.equal((await camp('mock_1')).effective, 'PAUSED')
  const log = S().logs.find((l) => l.kind === 'rule' && l.ok)
  const entry = await engine.undoLog(log.id)
  assert.equal((await camp('mock_1')).effective, 'ACTIVE')
  assert.equal(entry.kind, 'undo')
  assert.ok(S().logs.find((l) => l.id === log.id).undone)
  await assert.rejects(() => engine.undoLog(log.id), /đã được hoàn tác/)
  assert.ok(S().state.hold['r1:mock_1'] > Date.now()) // rule tạm hoãn để không tắt lại ngay
})

test('hoàn tác: ngân sách về giá trị cũ', async () => {
  await engine.act(await camp('mock_2'), { type: 'budget', mode: 'percent', value: 20 }, 'Lịch: t', { kind: 'schedule', refId: 's1' })
  assert.equal((await camp('mock_2')).dailyBudget, 360000)
  const log = S().logs.find((l) => l.kind === 'schedule' && l.ok)
  await engine.undoLog(log.id)
  assert.equal((await camp('mock_2')).dailyBudget, 300000)
})

test('hoàn tác: camp đã bị đổi tiếp thì cần xác nhận (force)', async () => {
  await engine.act(await camp('mock_1'), { type: 'off' }, 'Lịch: t', { kind: 'schedule', refId: 's1' })
  const log = S().logs.find((l) => l.ok)
  await fb.setStatus('mock_1', true) // ai đó bật lại bằng tay
  await assert.rejects(() => engine.undoLog(log.id), (e) => e.status === 409 && e.drift === true)
  const entry = await engine.undoLog(log.id, { force: true })
  assert.ok(entry.ok)
})

test('hoàn tác: không hoàn tác dòng chạy thử', async () => {
  Object.assign(S().settings, { mock: false, dryRun: true })
  const fake = S().logs; fake.unshift({ id: 'd1', ts: new Date().toISOString(), ok: true, dry: true, kind: 'rule', action: { type: 'off' }, target: { id: 'a' }, before: {}, after: { status: 'PAUSED' } })
  await assert.rejects(() => engine.undoLog('d1'), /chạy thử/)
})

/* ------------------------------------ Nhiều rule trong cùng một lượt chạy */
test('runRules: rule sau không tác động lên camp vừa bị rule trước tắt', async () => {
  const base = { enabled: true, minSpend: 0, cooldownHours: 24, allActive: true, level: 'campaign', range: 'today', metric: 'spend', op: '>', value: 1 }
  S().rules.push({ ...base, id: 'p1', name: 'Tắt', action: 'pause' })
  S().rules.push({ ...base, id: 'u1', name: 'Tăng', action: 'increase', pct: 20, maxBudget: 5000000 })
  const before = Object.fromEntries((await fb.listObjects(true)).map((o) => [o.id, o.dailyBudget]))
  await engine.runRules()
  const after = await fb.listObjects(true)
  for (const o of after.filter((x) => x.level === 'campaign' && x.effective === 'PAUSED' && before[x.id])) {
    if (S().logs.some((l) => l.refId === 'p1' && l.target && l.target.id === o.id && l.ok)) assert.equal(o.dailyBudget, before[o.id], `${o.name} vừa bị tắt nhưng ngân sách vẫn bị đổi`)
  }
  const paused = S().logs.filter((l) => l.refId === 'p1' && l.ok).map((l) => l.target.id)
  assert.ok(paused.length > 0)
  assert.equal(S().logs.filter((l) => l.refId === 'u1' && l.ok && paused.includes(l.target.id)).length, 0)
})

/* ------------------------------------------------------- lịch nhiều giờ */
test('lịch nhiều giờ: mỗi mốc đã tới chạy đúng 1 lần, mốc chưa tới thì chưa chạy', async () => {
  const tz = S().settings.timezone
  const hm = (offsetMin) => new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(Date.now() + offsetMin * 60e3))
  const past = hm(-3), later = hm(90)
  if (later < past) return // gần nửa đêm: bỏ qua cho khỏi lệch ngày
  S().schedules.push({ id: 'm1', name: 'Nhiều giờ', action: 'off', time: past, times: [past, later], days: [0, 1, 2, 3, 4, 5, 6], targets: ['mock_1'], enabled: true })
  await engine.tickSchedules()
  await engine.tickSchedules() // gọi lại không chạy lần 2
  const runs = S().logs.filter((l) => l.kind === 'schedule')
  assert.equal(runs.length, 1)
  assert.ok(Object.keys(S().state.fired).some((k) => k.endsWith(`:${past}`)))
  assert.ok(!Object.keys(S().state.fired).some((k) => k.endsWith(`:${later}`)))
})

/* ------------------------------------------------------- đếm kết quả */
test('kết quả "purchase": nhận cả Lượt mua trên Meta, không đếm trùng các tên khác nhau của cùng đơn', () => {
  const act = (t, v) => ({ action_type: t, value: String(v) })
  // tài khoản chốt đơn qua Messenger: không có "purchase", chỉ có omni_purchase / onsite_conversion.purchase
  const onsite = { spend: '8864906', actions: [act('onsite_conversion.purchase', 24), act('omni_purchase', 24), act('onsite_web_purchase', 24)], action_values: [act('omni_purchase', 25430000)] }
  const m = fb.metricsFrom(onsite, 'purchase')
  assert.equal(m.results, 24)
  assert.equal(Math.round(m.cpa), 369371)
  assert.equal(m.roas.toFixed(2), '2.87')
  assert.equal(m.revenue, 25430000) // doanh thu = giá trị chuyển đổi cùng loại kết quả (không cộng trùng các tên khác)
  assert.equal(fb.metricsFrom({ spend: '100', actions: [act('purchase', 2)] }, 'purchase').revenue, 0) // không có giá trị → 0
  // tài khoản chỉ có pixel web
  assert.equal(fb.metricsFrom({ spend: '100', actions: [act('purchase', 2), act('offsite_conversion.fb_pixel_purchase', 2)] }, 'purchase').results, 2)
  // loại không có bí danh: khớp đúng tên
  assert.equal(fb.metricsFrom({ spend: '100', actions: [act('link_click', 7)] }, 'link_click').results, 7)
  assert.equal(fb.metricsFrom({ spend: '100', actions: [] }, 'purchase').cpa, null)
})

test('cột kết quả phụ (cuộc trò chuyện, bắt đầu thanh toán, khách hàng tiềm năng, bình luận): độc lập với loại kết quả đã chọn', () => {
  const act = (t, v) => ({ action_type: t, value: String(v) })
  const row = {
    spend: '100',
    actions: [
      act('onsite_conversion.messaging_conversation_started_7d', 5),
      act('omni_initiated_checkout', 3),
      act('lead', 4),
      act('onsite_conversion.lead_grouped', 2),
      act('comment', 9),
    ],
  }
  const m = fb.metricsFrom(row, 'purchase') // dù đang chọn "purchase" làm kết quả chính, các cột phụ vẫn ra đủ
  assert.equal(m.conversations, 5)
  assert.equal(m.checkouts, 3)
  assert.equal(m.leads, 4)
  assert.equal(m.leadsOnMeta, 2)
  assert.equal(m.comments, 9)
  const empty = fb.metricsFrom({ spend: '0', actions: [] }, 'purchase')
  assert.deepEqual([empty.conversations, empty.checkouts, empty.leads, empty.leadsOnMeta, empty.comments], [0, 0, 0, 0, 0])
})

/* ------------------------------------------------- lịch theo điều kiện */
test('lịch theo điều kiện: camp ngân sách dưới 300k → đặt 350k, lọc lại lúc chạy, ghi 1 dòng tóm tắt', async () => {
  // dữ liệu giả: mock_3 (200k, đang tắt), mock_5 (150k, đang chạy) là 2 camp dưới 300k
  const sch = { id: 'f1', name: 'Nâng camp nhỏ', action: 'budget', mode: 'set', value: 350000, targetMode: 'filter', targets: [],
    filter: { level: 'campaign', op: 'lt', x: 300000, name: '', onlyRunning: false }, days: [0, 1, 2, 3, 4, 5, 6], times: ['06:00'], enabled: true }
  await engine.runSchedule(sch)
  assert.equal((await camp('mock_3')).dailyBudget, 350000)
  assert.equal((await camp('mock_5')).dailyBudget, 350000)
  assert.equal((await camp('mock_2')).dailyBudget, 300000) // = 300k không khớp "dưới"
  const logs = S().logs.filter((l) => l.refId === 'f1')
  assert.equal(logs.filter((l) => l.after).length, 2) // mỗi mục 1 dòng, hoàn tác được
  const sum = logs.find((l) => !l.after)
  assert.ok(sum.ok && sum.detail.includes('Khớp 2 mục') && sum.detail.includes('đã đổi 2'))

  await engine.runSchedule(sch) // chạy lại: đã đúng mức → không đổi thêm
  assert.equal(S().logs.filter((l) => l.refId === 'f1' && l.after).length, 2)
})

test('lịch theo điều kiện: chỉ mục đang chạy, cộng/trừ số tiền, bật/tắt theo tên', async () => {
  const base = { id: 'f2', name: 'Lọc', days: [0, 1, 2, 3, 4, 5, 6], times: ['06:00'], enabled: true, targetMode: 'filter', targets: [] }
  await engine.runSchedule({ ...base, action: 'budget', mode: 'add', value: -50000, filter: { level: 'campaign', op: 'lte', x: 300000, onlyRunning: true } })
  assert.equal((await camp('mock_5')).dailyBudget, 100000) // 150k đang chạy → -50k
  assert.equal((await camp('mock_2')).dailyBudget, 250000) // 300k đang chạy → -50k
  assert.equal((await camp('mock_3')).dailyBudget, 200000) // đang tắt → không đụng
  await engine.runSchedule({ ...base, id: 'f3', action: 'off', filter: { level: 'campaign', op: 'any', name: '[retarget]' } })
  assert.equal((await camp('mock_2')).effective, 'PAUSED')
  assert.equal((await camp('mock_1')).effective, 'ACTIVE')
})

test('lịch không khớp mục nào: ghi 1 dòng, không gửi gì', async () => {
  await engine.runSchedule({ id: 'f4', name: 'Không khớp', action: 'on', targetMode: 'filter', targets: [], filter: { level: 'campaign', op: 'gt', x: 1e9 }, days: [0], times: ['06:00'], enabled: true })
  const l = S().logs.find((x) => x.refId === 'f4')
  assert.ok(l.ok && l.detail.includes('Không có mục nào khớp'))
})

test('lịch theo điều kiện: bỏ qua mục đã loại trừ, vẫn áp dụng cho mục khớp còn lại', async () => {
  await engine.runSchedule({ id: 'f5', name: 'Tắt trừ 1', action: 'off', targetMode: 'filter', targets: [], exclude: ['mock_1'],
    filter: { level: 'campaign', op: 'gte', x: 500000 }, days: [0], times: ['06:00'], enabled: true })
  assert.equal((await camp('mock_1')).effective, 'ACTIVE') // 500k nhưng bị loại trừ
  assert.equal((await camp('mock_4')).effective, 'PAUSED') // 800k → tắt
  const sum = S().logs.find((l) => l.refId === 'f5' && !l.after)
  assert.ok(sum.detail.includes('trừ 1 mục') && sum.detail.includes('Khớp 1 mục'))
})

test('báo cáo: nhiều tài khoản quảng cáo → mỗi tài khoản một phần', async () => {
  const notify = require('../lib/notify')
  const orig = notify.send
  let sent = ''
  notify.send = async (t) => { sent = t; return { configured: true, results: [{ id: '111111', ok: true }] } }
  try { await engine.sendReport() } finally { notify.send = orig }
  assert.ok(sent.includes('Tài khoản mẫu A') && sent.includes('Tài khoản mẫu B'))
  assert.equal((sent.match(/Đang chạy:/g) || []).length, 2)
})
