// Rule cấp nhóm QC: chọn đúng cấp, bỏ qua mục không có ngân sách riêng khi đổi ngân sách (CBO/ABO), kiểm tra khi lưu.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateRule } from '../shared/validate.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-rules-adset-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')
const S = () => store.get()

beforeEach(() => {
  const d = S()
  d.logs.length = 0; d.rules.length = 0; d.schedules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true, killSwitchEnabled: false, dailySpendLimit: 0, killScope: 'total', accountTargets: {}, dailyChangeCapPct: 30 })
  fb.resetMock(); fb.resetCache()
})

const o = (id, level, extra = {}) => ({ id, name: `${level} ${id}`, level, status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 100000, accountId: '1', accountName: 'TK 1', ...extra })
// c1: CBO (ngân sách ở camp, nhóm a1 không có); c2: ABO (camp không có, nhóm a2/a3 có)
const objs = () => [
  o('c1', 'campaign'), o('a1', 'adset', { campaignId: 'c1', dailyBudget: null }),
  o('c2', 'campaign', { dailyBudget: null }), o('a2', 'adset', { campaignId: 'c2' }), o('a3', 'adset', { campaignId: 'c2', effective: 'PAUSED', status: 'PAUSED' }),
]
const M = { spend: 500000, impressions: 100000, clicks: 1000, results: 1, revenue: 0, cpa: 500000, roas: null }
const mapAll = () => Object.fromEntries(objs().map((x) => [x.id, M]))
const rule = (extra) => ({ id: 'r1', name: 'x', conditions: [{ metric: 'cpa', op: '>', value: 100000 }], match: 'all', action: 'pause', allActive: true, level: 'adset', range: 'last_3d', minSpend: 0, ...extra })

test('rule nhóm QC "tất cả đang chạy": chỉ xét nhóm QC đang chạy, không đụng chiến dịch', () => {
  const ds = engine.evaluateRule(rule(), objs(), mapAll)
  assert.deepEqual(ds.map((d) => d.obj.id), ['a1', 'a2'])
  assert.ok(ds.every((d) => d.status === 'match'))
  assert.equal(ds[0].plan.detail, 'Tắt nhóm QC')
})

test('rule cũ không có level vẫn là cấp chiến dịch', () => {
  const { level, ...old } = rule()
  assert.deepEqual(engine.evaluateRule(old, objs(), mapAll).map((d) => d.obj.id), ['c1', 'c2'])
})

test('rule đổi ngân sách: nhóm QC trong camp CBO bị bỏ qua (có lý do), nhóm ABO được đổi', () => {
  const ds = engine.evaluateRule(rule({ action: 'decrease', pct: 20, cooldownHours: 24 }), objs(), mapAll)
  const a1 = ds.find((d) => d.obj.id === 'a1'), a2 = ds.find((d) => d.obj.id === 'a2')
  assert.equal(a1.status, 'skip'); assert.equal(a1.code, 'nobudget'); assert.match(a1.reason, /CBO/)
  assert.equal(a2.status, 'match'); assert.equal(a2.plan.next, 80000)
})

test('rule đổi ngân sách cấp chiến dịch: camp ABO bị bỏ qua, gợi ý dùng rule nhóm QC', () => {
  const ds = engine.evaluateRule(rule({ level: 'campaign', action: 'increase', pct: 20, cooldownHours: 24 }), objs(), mapAll)
  const c2 = ds.find((d) => d.obj.id === 'c2')
  assert.equal(c2.code, 'nobudget'); assert.match(c2.reason, /nhóm QC/)
  assert.equal(ds.find((d) => d.obj.id === 'c1').status, 'match')
})

test('rule tắt vẫn áp dụng cho nhóm QC trong camp CBO', () => {
  const ds = engine.evaluateRule(rule({ allActive: false, targets: ['a1'] }), objs(), mapAll)
  assert.equal(ds[0].status, 'match')
})

test('kiểm tra khi lưu: giữ cấp nhóm QC, mục chọn phải đúng cấp, cảnh báo mục không có ngân sách', () => {
  const ok = { name: 'r', conditions: [{ metric: 'cpa', op: '>', value: 1 }], range: 'last_3d', minSpend: 100000, action: 'pause', cooldownHours: 24 }
  assert.equal(validateRule({ ...ok, level: 'adset' }).value.level, 'adset')
  assert.equal(validateRule({ ...ok }).value.level, 'campaign')
  assert.equal(validateRule({ ...ok, level: 'lung tung' }).value.level, 'campaign')

  const wrong = validateRule({ ...ok, level: 'adset', allActive: false, targets: ['c1'] }, { objs: objs() })
  assert.match(wrong.errors.targets, /không phải nhóm QC/)
  assert.ok(validateRule({ ...ok, level: 'adset', allActive: false, targets: ['a1', 'a2'] }, { objs: objs() }).ok)

  const dec = { ...ok, action: 'decrease', pct: 20, minBudget: 50000 }
  const some = validateRule({ ...dec, level: 'adset' }, { objs: objs() })
  assert.ok(some.warnings.some((w) => /1 nhóm QC không có ngân sách riêng/.test(w)), some.warnings.join('\n'))
  const none = validateRule({ ...dec, level: 'adset', allActive: false, targets: ['a1'] }, { objs: objs() })
  assert.ok(none.warnings.some((w) => /Không nhóm QC nào đã chọn có ngân sách riêng/.test(w) && /rule cấp chiến dịch/.test(w)), none.warnings.join('\n'))
  assert.ok(!validateRule({ ...ok, level: 'adset', allActive: false, targets: ['a1'] }, { objs: objs() }).warnings.some((w) => /ngân sách riêng/.test(w)), 'rule tắt không cần cảnh báo ngân sách')
})
