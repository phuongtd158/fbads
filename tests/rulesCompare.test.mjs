// Rule so sánh giữa hai khoảng thời gian: "CPA hôm nay > 130% CPA 7 ngày gần nhất".
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateRule, compareThreshold } from '../shared/validate.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-compare-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')

beforeEach(() => {
  const d = store.get()
  d.logs.length = 0; d.rules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {}, resume: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true })
  fb.resetMock(); fb.resetCache()
})

const base = { name: 'CPA tăng', range: 'today', minSpend: 100000, action: 'notify', cooldownHours: 24, allActive: true }
const cmp = (over = {}) => ({ metric: 'cpa', op: '>', vs: 'range', compareRange: 'last_7d', factor: 130, ...over })
const obj = (id) => ({ id, name: `Camp ${id}`, level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 500000, accountId: 'a1' })
const m = (spend, results) => ({ spend, impressions: spend * 10, reach: spend * 5, clicks: spend / 1000, results, cpa: results ? spend / results : null, roas: null, revenue: 0 })

test('validate: nhận điều kiện so với khoảng khác, chặn khoảng trùng và % sai', () => {
  const ok = validateRule({ ...base, conditions: [cmp()] })
  assert.equal(ok.ok, true, JSON.stringify(ok.errors))
  assert.deepEqual(ok.value.conditions[0], { metric: 'cpa', op: '>', vs: 'range', compareRange: 'last_7d', factor: 130, value: 0 })
  assert.equal(validateRule({ ...base, range: 'last_7d', conditions: [cmp()] }).ok, false, 'khoảng so sánh trùng khoảng của rule')
  assert.equal(validateRule({ ...base, conditions: [cmp({ factor: 0 })] }).ok, false)
  assert.equal(validateRule({ ...base, conditions: [cmp({ compareRange: 'last_30d' })] }).ok, false)
  // mọi số liệu đều so được với khoảng khác (không như "so với mục tiêu" chỉ có CPA/ROAS/chi tiêu)
  assert.equal(validateRule({ ...base, conditions: [cmp({ metric: 'ctr', op: '<', factor: 70 })] }).ok, true)
})

test('validate: cảnh báo khi so số liệu dạng tổng của hôm nay (chưa hết ngày)', () => {
  const r = validateRule({ ...base, conditions: [cmp({ metric: 'spend' })] })
  assert.ok(r.warnings.some((w) => /mới tính đến giờ hiện tại/.test(w)))
  assert.ok(!validateRule({ ...base, conditions: [cmp()] }).warnings.some((w) => /mới tính đến giờ hiện tại/.test(w)))
})

test('ngưỡng: tỉ lệ so thẳng, số liệu dạng tổng chia trung bình mỗi ngày, chưa có số liệu = null', () => {
  assert.equal(compareThreshold(cmp(), 100000), 130000)
  assert.equal(compareThreshold(cmp({ metric: 'results', factor: 50 }), 70), 5) // 70 kết quả / 7 ngày = 10/ngày × 50%
  assert.equal(compareThreshold(cmp(), 0), null)
  assert.equal(compareThreshold(cmp(), Infinity), null)
})

test('engine: CPA hôm nay cao hơn 130% của 7 ngày thì khớp, thấp hơn thì không', () => {
  const rule = { ...base, id: 'r1', conditions: [cmp()] }
  const maps = {
    today: { c1: m(300000, 1), c2: m(300000, 3) },          // c1: CPA 300k; c2: CPA 100k
    last_7d: { c1: m(1400000, 14), c2: m(1400000, 14) },    // CPA 7 ngày = 100k → ngưỡng 130k
  }
  const ds = engine.evaluateRule(rule, [obj('c1'), obj('c2')], (r) => maps[r])
  const byId = Object.fromEntries(ds.map((d) => [d.obj.id, d]))
  assert.equal(byId.c1.status, 'match')
  assert.equal(byId.c1.conds[0].threshold, 130000)
  assert.equal(byId.c2.status, 'nomatch')
})

test('engine: khoảng so sánh chưa có số liệu thì bỏ qua và nói rõ lý do', () => {
  const rule = { ...base, id: 'r1', conditions: [cmp()] }
  const maps = { today: { c1: m(300000, 1) }, last_7d: {} } // camp mới, 7 ngày trước chưa chạy
  const [d] = engine.evaluateRule(rule, [obj('c1')], (r) => maps[r])
  assert.equal(d.status, 'skip')
  assert.equal(d.code, 'nobaseline')
  assert.match(d.reason, /Chưa có số liệu CPA 7 ngày gần nhất/)
})

test('engine: số tin nhắn hôm qua thấp hơn 50% trung bình 3 ngày', () => {
  const rule = { ...base, id: 'r1', range: 'yesterday', conditions: [{ metric: 'messages', op: '<', vs: 'range', compareRange: 'last_3d', factor: 50 }] }
  const maps = { yesterday: { c1: { ...m(300000, 0), conversations: 2 } }, last_3d: { c1: { ...m(900000, 0), conversations: 30 } } } // 30/3 = 10/ngày → ngưỡng 5
  const [d] = engine.evaluateRule(rule, [obj('c1')], (r) => maps[r])
  assert.equal(d.conds[0].threshold, 5)
  assert.equal(d.status, 'match')
})

test('xem trước và chạy thật dùng số liệu của cả khoảng so sánh (dữ liệu giả)', async () => {
  const r = validateRule({ ...base, range: 'yesterday', minSpend: 1, conditions: [cmp({ metric: 'spend', op: '>', factor: 50 })] })
  assert.equal(r.ok, true)
  const pv = await engine.previewRule({ ...r.value, id: 'p1' })
  assert.ok(pv.items.length > 0)
  const withTh = pv.items.filter((i) => i.conds[0].threshold != null)
  assert.ok(withTh.length > 0, 'có ngưỡng tính từ số liệu 7 ngày')
  assert.equal(pv.items[0].conds[0].compareRange, 'last_7d')
})
