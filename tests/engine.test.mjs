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
