// Ngưỡng chi tiêu nâng theo số kết quả: "Chi tiêu > 150.000, từ 2 lead thì 200.000" trong 1 rule.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateRule, spendTierOf, MAX_TIERS } from '../shared/validate.mjs'
import { ruleName } from '../shared/names.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-tiers-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')

beforeEach(() => {
  const d = store.get()
  d.logs.length = 0; d.rules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true, accountTargets: {} })
  fb.resetMock(); fb.resetCache()
})

const okRule = { name: 'r', range: 'today', action: 'decrease', pct: 50, cooldownHours: 24, allActive: true, level: 'adset' }
const cond = (extra = {}) => ({ metric: 'spend', op: '>', value: 150000, tierMetric: 'leads', tiers: [{ count: 2, value: 200000 }], ...extra })

test('kiểm tra dữ liệu: bậc hợp lệ được giữ, rule chỉ có điều kiện chi tiêu không cần chi tối thiểu', () => {
  const r = validateRule({ ...okRule, conditions: [cond()] })
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  assert.deepEqual(r.value.conditions[0], { metric: 'spend', op: '>', value: 150000, tierMetric: 'leads', tiers: [{ count: 2, value: 200000 }] })
  // không có bậc thì điều kiện giữ nguyên như cũ
  assert.deepEqual(validateRule({ ...okRule, conditions: [{ metric: 'spend', op: '>', value: 150000, tiers: [] }] }).value.conditions[0], { metric: 'spend', op: '>', value: 150000 })
})

test('kiểm tra dữ liệu: bậc sai bị báo lỗi', () => {
  const err = (c) => validateRule({ ...okRule, conditions: [c] }).errors['c0.tiers']
  assert.ok(err(cond({ metric: 'cpa' })), 'chỉ cho chi tiêu')
  assert.ok(err(cond({ op: '<' })), 'chỉ cho lớn hơn')
  assert.ok(err(cond({ tierMetric: 'roas' })), 'loại kết quả không hợp lệ')
  assert.ok(err(cond({ tiers: [{ count: 0, value: 200000 }] })), 'số kết quả ≥ 1')
  assert.ok(err(cond({ tiers: [{ count: 1.5, value: 200000 }] })), 'số nguyên')
  assert.ok(err(cond({ tiers: [{ count: 2, value: 120000 }] })), 'ngưỡng bậc phải lớn hơn ngưỡng gốc')
  assert.ok(err(cond({ tiers: [{ count: 2, value: 200000 }, { count: 2, value: 300000 }] })), 'số kết quả tăng dần')
  assert.ok(err(cond({ tiers: [{ count: 2, value: 200000 }, { count: 4, value: 180000 }] })), 'ngưỡng tăng dần')
  const many = Array.from({ length: MAX_TIERS + 1 }, (_, i) => ({ count: i + 1, value: 200000 + i * 10000 }))
  assert.ok(err(cond({ tiers: many })), `tối đa ${MAX_TIERS} bậc`)
})

test('chọn bậc: bậc cao nhất đã đạt, chưa đạt thì ngưỡng gốc', () => {
  const c = cond({ tiers: [{ count: 2, value: 200000 }, { count: 4, value: 300000 }] })
  assert.deepEqual([0, 1, 2, 3, 4, 9].map((n) => spendTierOf(c, n).value), [150000, 150000, 200000, 200000, 300000, 300000])
})

test('tên tự đặt có phần bậc', () => {
  assert.equal(ruleName({ ...okRule, conditions: [cond()] }), 'Giảm NS nhóm QC Chi tiêu > 150.000, 2 Lead: 200.000')
})

test('đánh giá: 0/1 lead giảm khi chi > 150k, từ 2 lead đợi tới > 200k', () => {
  const adset = (id) => ({ id, name: id, level: 'adset', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 500000, accountId: 'a1' })
  const cases = { // id: [chi tiêu, số lead, có khớp không]
    s140l0: [140000, 0, false], s160l0: [160000, 0, true], s160l1: [160000, 1, true],
    s160l2: [160000, 2, false], s199l3: [199000, 3, false], s210l2: [210000, 2, true],
  }
  const objs = Object.keys(cases).map(adset)
  const map = Object.fromEntries(Object.entries(cases).map(([id, [spend, leads]]) => [id, { spend, leads, results: leads, impressions: 1000, clicks: 10 }]))
  const rule = { id: 'r1', name: 'x', ...okRule, minSpend: 0, conditions: [cond()] }
  const ds = engine.evaluateRule(rule, objs, () => map)
  for (const d of ds) {
    assert.equal(d.hit, cases[d.obj.id][2], d.obj.id)
    assert.equal(d.conds[0].threshold, cases[d.obj.id][1] >= 2 ? 200000 : 150000, d.obj.id)
    assert.equal(d.conds[0].tierCount, cases[d.obj.id][1])
  }
})

test('chạy thật: nhật ký ghi số lead và ngưỡng đã dùng', async () => {
  const d = store.get()
  d.rules.push({ id: 'rt', name: 'Bậc', enabled: true, ...okRule, action: 'notify', minSpend: 0, cooldownHours: 1, level: 'campaign', conditions: [cond({ value: 1, tiers: [{ count: 1000, value: 2 }] })] })
  await engine.runRules()
  const l = d.logs.find((x) => x.refId === 'rt')
  assert.ok(l, 'có ít nhất 1 camp mock khớp')
  const c0 = l.condition.conditions[0]
  assert.equal(c0.tierMetric, 'leads')
  assert.equal(typeof c0.tierCount, 'number')
  assert.match(l.detail, /đã có \d+ Lead/)
})
