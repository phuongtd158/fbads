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
