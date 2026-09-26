// Số liệu tin nhắn/lead cho rule và "tắt hôm nay, mai tự bật lại". Dữ liệu giả (mock), ghi vào thư mục tạm.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateRule } from '../shared/validate.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-smart-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')
const S = () => store.get()
const camp = async (id) => (await fb.listObjects(true)).find((o) => o.id === id)

beforeEach(() => {
  const d = S()
  d.logs.length = 0; d.rules.length = 0; d.schedules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {}, resume: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true, dailyChangeCapPct: 30, killSwitchEnabled: false, dailySpendLimit: 0 })
  fb.resetMock(); fb.resetCache()
})

const okRule = { name: 'r', range: 'today', minSpend: 100000, action: 'pause', cooldownHours: 24, allActive: true }

/* ------------------------------------------------ Số liệu tin nhắn / lead */
test('metricValue: tin nhắn, lead và chi phí mỗi tin nhắn/lead (chưa có mà đã chi = ∞)', () => {
  const m = { spend: 300000, conversations: 3, leads: 0 }
  assert.equal(engine.metricValue(m, 'messages'), 3)
  assert.equal(engine.metricValue(m, 'costPerMessage'), 100000)
  assert.equal(engine.metricValue(m, 'leads'), 0)
  assert.equal(engine.metricValue(m, 'costPerLead'), Infinity)
  assert.equal(engine.metricValue({ spend: 0 }, 'costPerLead'), 0)
})

test('validate: nhận số liệu mới; chi phí "lớn hơn 0" bị chặn', () => {
  assert.equal(validateRule({ ...okRule, conditions: [{ metric: 'costPerMessage', op: '>', value: 80000 }] }).ok, true)
  assert.equal(validateRule({ ...okRule, conditions: [{ metric: 'leads', op: '<', value: 1 }] }).ok, true)
  assert.ok(validateRule({ ...okRule, conditions: [{ metric: 'costPerLead', op: '>', value: 0 }] }).errors['c0.value'])
})

/* ------------------------------------------------ Tắt hôm nay, mai bật lại */
test('validate: tự bật lại chỉ đi với rule tắt; giờ bật lại phải hợp lệ', () => {
  const r = validateRule({ ...okRule, metric: 'cpa', op: '>', value: 150000, resume: 'nextday' })
  assert.equal(r.ok, true)
  assert.deepEqual([r.value.resume, r.value.resumeAt], ['nextday', '06:00'])
  assert.equal(validateRule({ ...okRule, metric: 'cpa', op: '>', value: 1, resume: 'nextday', resumeAt: '25:00' }).errors.resumeAt, 'Giờ bật lại không hợp lệ (dạng HH:MM, ví dụ 06:00)')
  const n = validateRule({ ...okRule, action: 'notify', cooldownHours: 2, metric: 'cpa', op: '>', value: 1, resume: 'nextday' })
  assert.deepEqual([n.value.resume, n.value.resumeAt], ['', ''])
  const w = validateRule({ ...okRule, range: 'last_3d', metric: 'cpa', op: '>', value: 150000, resume: 'nextday' })
  assert.ok(w.warnings.some((x) => x.includes('bị tắt lại')))
})

const pauseRule = { id: 'p1', name: 'Cắt lỗ', enabled: true, metric: 'spend', op: '>', value: 1, minSpend: 0, action: 'pause', cooldownHours: 24, allActive: true, level: 'campaign', range: 'today', resume: 'nextday', resumeAt: '00:00' }

test('runRules ghi hẹn bật lại; tới ngày sau tickResumes bật lại và xoá thời gian nghỉ', async () => {
  S().settings.dryRun = false
  S().rules.push({ ...pauseRule })
  await engine.runRules()
  const keys = Object.keys(S().state.resume)
  assert.ok(keys.length > 0)
  const id = S().state.resume[keys[0]].objId
  assert.equal((await camp(id)).effective, 'PAUSED')
  await engine.tickResumes() // cùng ngày: chưa bật
  assert.equal((await camp(id)).effective, 'PAUSED')
  for (const k of keys) S().state.resume[k].date = '2000-01-01' // giả như đã sang ngày mới
  await engine.tickResumes()
  assert.equal((await camp(id)).effective, 'ACTIVE')
  assert.deepEqual(S().state.resume, {})
  assert.equal(S().state.lastRule[`p1:${id}`], 0)
  assert.ok(S().logs.some((l) => l.ok && l.target.id === id && l.after && l.after.status === 'ACTIVE' && l.source.includes('bật lại')))
})

test('rule bị xoá hoặc camp đã bật tay thì bỏ hẹn', async () => {
  S().rules.push({ ...pauseRule })
  S().settings.dryRun = false
  await fb.setStatus('mock_1', false)
  S().state.resume = { 'p1:mock_1': { ruleId: 'p1', objId: 'mock_1', date: '2000-01-01' }, 'gone:mock_2': { ruleId: 'gone', objId: 'mock_2', date: '2000-01-01' } }
  await fb.setStatus('mock_1', true) // bạn đã bật tay
  const n = S().logs.length
  await engine.tickResumes()
  assert.deepEqual(S().state.resume, {})
  assert.equal(S().logs.length, n, 'không có gì để bật')
})

/* ------------------------------------------------ Hoạt động gần đây */
test('ruleActivity: đếm tác động/lỗi 7 ngày, bỏ dòng bỏ qua, lần gần nhất, hẹn bật lại', () => {
  const now = Date.parse('2026-09-26T10:00:00Z'), iso = (h) => new Date(now - h * 3600e3).toISOString()
  S().logs.push(
    { kind: 'rule', refId: 'a', ts: iso(1), ok: true, name: 'Camp 1', detail: 'Tắt camp' },
    { kind: 'rule', refId: 'a', ts: iso(2), ok: true, skipped: true, name: 'Camp 2', detail: 'Bỏ qua' },
    { kind: 'rule', refId: 'a', ts: iso(3), ok: false, name: 'Camp 3', detail: 'lỗi' },
    { kind: 'rule', refId: 'a', ts: iso(24 * 8), ok: true, name: 'Camp 4', detail: 'cũ' },
    { kind: 'schedule', refId: 'a', ts: iso(1), ok: true, name: 'x', detail: 'x' },
  )
  S().state.resume = { 'b:1': { ruleId: 'b', objId: '1', date: '2026-09-26' } }
  const a = engine.ruleActivity(now)
  assert.deepEqual([a.a.acts, a.a.errors, a.a.last.name, a.a.resumePending], [1, 1, 'Camp 1', 0])
  assert.deepEqual([a.b.acts, a.b.last, a.b.resumePending], [0, null, 1])
})

/* ------------------------------------------------ Chỉ xét mục đang phân phối */
test('evaluateRule: camp bật nhưng mọi nhóm QC đã tắt/hết hạn thì không tính là đang chạy', async () => {
  const { deliveryMap, DELIVERY } = await import('../shared/delivery.mjs')
  const objs = [
    { id: 'c1', name: 'Chạy', level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 100000 },
    { id: 'a1', level: 'adset', campaignId: 'c1', status: 'ACTIVE', effective: 'ACTIVE' },
    { id: 'c2', name: 'Nhóm tắt', level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 100000 },
    { id: 'a2', level: 'adset', campaignId: 'c2', status: 'PAUSED', effective: 'PAUSED' },
    { id: 'c3', name: 'Hết hạn', level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 100000 },
    { id: 'a3', level: 'adset', campaignId: 'c3', status: 'ACTIVE', effective: 'ACTIVE', endTime: Date.now() - 86400e3 },
  ]
  const dm = deliveryMap(objs)
  const running = { running: (o) => !!(DELIVERY[dm[o.id]] || {}).running, label: (o) => DELIVERY[dm[o.id]].label }
  const rule = { id: 'r', name: 'x', action: 'notify', metric: 'spend', op: '>', value: 1, minSpend: 0, allActive: true, level: 'campaign', range: 'today' }
  const map = () => ({ c1: { spend: 5 }, c2: { spend: 5 }, c3: { spend: 5 } })
  assert.deepEqual(engine.evaluateRule(rule, objs, map, { running }).map((d) => d.obj.id), ['c1'])
  const picked = engine.evaluateRule({ ...rule, allActive: false, targets: ['c2', 'c3'] }, objs, map, { running })
  assert.deepEqual(picked.map((d) => [d.code, d.reason]), [['inactive', 'Camp không đang chạy (Nhóm quảng cáo đang tắt)'], ['inactive', 'Camp không đang chạy (Hoàn tất)']])
})

/* ------------------------------------------------ Chuẩn ngành: tần suất, chi tiêu so với CPA mục tiêu, đổi theo số tiền */
test('tần suất = hiển thị / người xem; chưa có reach thì 0', () => {
  assert.equal(engine.metricValue({ spend: 1, impressions: 3000, reach: 1000 }, 'frequency'), 3)
  assert.equal(engine.metricValue({ spend: 1, impressions: 3000 }, 'frequency'), 0)
  assert.equal(validateRule({ ...okRule, range: 'last_7d', action: 'notify', cooldownHours: 24, conditions: [{ metric: 'frequency', op: '>', value: 3.5 }] }).ok, true)
})

test('cắt lỗ: chi tiêu so với % CPA mục tiêu của tài khoản', () => {
  S().settings.accountTargets = { acc1: { cpa: 100000 } }
  const obj = { id: 'c1', name: 'c', level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 100000, accountId: 'acc1' }
  const rule = { id: 'r', name: 'x', action: 'pause', allActive: true, level: 'campaign', range: 'today', minSpend: 0, match: 'all',
    conditions: [{ metric: 'spend', op: '>', vs: 'target', factor: 200 }, { metric: 'results', op: '<', value: 1 }] }
  const v = validateRule({ ...rule, minSpend: 100000, cooldownHours: 24 }, { accountTargets: { acc2: {} }, accounts: [{ id: 'acc2', name: 'B' }] })
  assert.equal(v.ok, true)
  assert.ok(v.warnings.some((w) => w.includes('chưa đặt mục tiêu CPA')))
  const run = (spend, results) => engine.evaluateRule(rule, [obj], () => ({ c1: { spend, results } }))[0]
  assert.equal(run(250000, 0).hit, true)
  assert.equal(run(150000, 0).hit, false)
  assert.equal(run(250000, 1).hit, false)
  assert.equal(run(250000, 0).conds[0].threshold, 200000)
})

test('tăng/giảm ngân sách theo số tiền cố định', () => {
  const r = validateRule({ ...okRule, metric: 'roas', op: '>', value: 3, action: 'increase', budgetMode: 'amount', amount: '200000', pct: 20, maxBudget: 2000000, cooldownHours: 24 })
  assert.equal(r.ok, true)
  assert.deepEqual([r.value.budgetMode, r.value.amount, r.value.pct], ['amount', 200000, 0])
  assert.ok(validateRule({ ...okRule, metric: 'roas', op: '>', value: 3, action: 'decrease', budgetMode: 'amount', amount: 0, cooldownHours: 24 }).errors.amount)
  assert.equal(validateRule({ ...okRule, metric: 'cpa', op: '>', value: 1, budgetMode: 'amount', amount: 5 }).value.budgetMode, 'percent') // rule tắt: bỏ qua
  const obj = { id: 'c1', name: 'c', level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 1000000 }
  const d = engine.evaluateRule({ ...r.value, id: 'r', minSpend: 0 }, [obj], () => ({ c1: { spend: 100, roas: 5 } }))[0]
  assert.deepEqual([d.action.mode, d.action.value], ['add', 200000])
  assert.equal(d.plan.next, 1200000)
})
