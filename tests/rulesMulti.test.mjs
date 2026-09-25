// Rule nhiều điều kiện (VÀ/HOẶC), mục tiêu theo tài khoản, phạm vi tài khoản, dừng khẩn theo tài khoản.
// Số liệu tự dựng (không phụ thuộc giờ trong ngày); dữ liệu ghi vào thư mục tạm.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateRule, validateSettings, conditionsOf, MAX_CONDITIONS } from '../shared/validate.mjs'
import { derive } from '../shared/metrics.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-rules-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')
const S = () => store.get()

beforeEach(() => {
  const d = S()
  d.logs.length = 0; d.rules.length = 0; d.schedules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true, killSwitchEnabled: false, dailySpendLimit: 0, killScope: 'total', accountTargets: {} })
  fb.resetMock(); fb.resetCache()
})

// ----- dữ liệu dựng tay -----
const camp = (id, acc, extra = {}) => ({ id, name: `Camp ${id}`, level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 100000, accountId: acc, accountName: `TK ${acc}`, ...extra })
const M = (o) => ({ spend: 500000, impressions: 100000, clicks: 1000, results: 2, revenue: 0, cpa: null, roas: null, ...o })
const mapOf = (m) => () => m
const hits = (ds) => ds.filter((d) => d.hit).map((d) => d.obj.id)
const base = { id: 'r1', name: 'x', action: 'pause', allActive: true, level: 'campaign', range: 'last_3d', minSpend: 0 }

// =============== kiểm tra dữ liệu ===============
const okRule = { name: 'r', range: 'last_3d', minSpend: 100000, action: 'pause', cooldownHours: 24, allActive: true }

test('rule cũ (1 điều kiện ở ngoài cùng) vẫn hợp lệ và được chuyển thành conditions', () => {
  const r = validateRule({ ...okRule, metric: 'cpa', op: '>', value: 150000 })
  assert.equal(r.ok, true)
  assert.deepEqual(r.value.conditions, [{ metric: 'cpa', op: '>', value: 150000 }])
  assert.equal(r.value.match, 'all')
  assert.deepEqual(r.value.accountIds, [])
  assert.deepEqual(conditionsOf({ metric: 'cpa', op: '>', value: 5 }), [{ metric: 'cpa', op: '>', value: 5 }]) // rule cũ chưa có conditions
})

test('nhiều điều kiện: 3 trường cũ = điều kiện đầu, lỗi báo theo từng điều kiện', () => {
  const r = validateRule({ ...okRule, conditions: [{ metric: 'cpa', op: '>', value: 150000 }, { metric: 'roas', op: '<', value: 1.5 }], match: 'any' })
  assert.equal(r.ok, true)
  assert.deepEqual([r.value.metric, r.value.op, r.value.value, r.value.match], ['cpa', '>', 150000, 'any'])
  assert.equal(r.value.conditions.length, 2)
  const bad = validateRule({ ...okRule, conditions: [{ metric: 'cpa', op: '>', value: 150000 }, { metric: 'xyz', op: '>', value: 1 }, { metric: 'roas', op: '<', value: -3 }] })
  assert.equal(bad.ok, false)
  assert.ok(bad['errors'] && bad.errors['c1.metric'] && bad.errors['c2.value'])
  assert.ok(!bad.errors['c0.value'] && !bad.errors.value, 'điều kiện đầu đúng thì không có lỗi cũ')
})

test('tối đa 5 điều kiện; match chỉ nhận all/any', () => {
  const many = Array.from({ length: MAX_CONDITIONS + 1 }, (_, i) => ({ metric: 'cpa', op: '>', value: 100000 + i }))
  assert.ok(validateRule({ ...okRule, conditions: many }).errors.conditions)
  assert.equal(validateRule({ ...okRule, conditions: many.slice(0, MAX_CONDITIONS) }).ok, true)
  assert.equal(validateRule({ ...okRule, metric: 'cpa', op: '>', value: 1, match: 'linh tinh' }).value.match, 'all')
})

test('điều kiện so với mục tiêu: chỉ CPA/ROAS, phần trăm hợp lệ, không cần nhập ngưỡng', () => {
  const t = validateRule({ ...okRule, conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 120 }] })
  assert.equal(t.ok, true)
  assert.deepEqual(t.value.conditions[0], { metric: 'cpa', op: '>', vs: 'target', factor: 120, value: 0 })
  assert.equal(validateRule({ ...okRule, conditions: [{ metric: 'cpa', op: '>', vs: 'target' }] }).value.conditions[0].factor, 100) // mặc định 100%
  assert.ok(validateRule({ ...okRule, conditions: [{ metric: 'spend', op: '>', vs: 'target', factor: 100 }] }).errors.metric)
  for (const f of [0, -5, 1001, 'abc']) assert.ok(validateRule({ ...okRule, conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: f }] }).errors.value, String(f))
})

test('số liệu mới CTR/CPC/CPM: kiểm tra ngưỡng như các số liệu khác', () => {
  assert.equal(validateRule({ ...okRule, metric: 'ctr', op: '<', value: 0.8 }).ok, true)
  assert.ok(validateRule({ ...okRule, metric: 'ctr', op: '<', value: 150 }).errors.value)
  assert.ok(validateRule({ ...okRule, metric: 'cpc', op: '>', value: 0 }).errors.value) // > 0 khớp mọi camp
  assert.equal(validateRule({ ...okRule, metric: 'cpm', op: '>', value: 90000 }).ok, true)
})

test('điều kiện mâu thuẫn (VÀ) bị cảnh báo; HOẶC thì không', () => {
  const c = [{ metric: 'cpa', op: '>', value: 200000 }, { metric: 'cpa', op: '<', value: 100000 }]
  assert.ok(validateRule({ ...okRule, conditions: c, match: 'all' }).warnings.some((w) => /mâu thuẫn/.test(w)))
  assert.ok(!validateRule({ ...okRule, conditions: c, match: 'any' }).warnings.some((w) => /mâu thuẫn/.test(w)))
  const fine = [{ metric: 'cpa', op: '>', value: 100000 }, { metric: 'cpa', op: '<', value: 200000 }]
  assert.ok(!validateRule({ ...okRule, conditions: fine }).warnings.some((w) => /mâu thuẫn/.test(w)))
})

test('cần chi tiêu tối thiểu khi có điều kiện không phải "Chi tiêu"', () => {
  assert.ok(validateRule({ ...okRule, minSpend: 0, conditions: [{ metric: 'spend', op: '>', value: 1e6 }, { metric: 'roas', op: '<', value: 1 }] }).errors.minSpend)
  assert.equal(validateRule({ ...okRule, minSpend: 0, conditions: [{ metric: 'spend', op: '>', value: 1e6 }] }).ok, true)
})

test('phạm vi tài khoản: chỉ giữ khi áp dụng "tất cả camp đang chạy"; cảnh báo tài khoản không còn quản lý', () => {
  const accounts = [{ id: 'A', name: 'Shop A' }, { id: 'B', name: 'Shop B' }]
  const r = validateRule({ ...okRule, metric: 'cpa', op: '>', value: 1e5, accountIds: ['A', 'A', ' B ', ''] }, { accounts })
  assert.deepEqual(r.value.accountIds, ['A', 'B'])
  const pick = validateRule({ ...okRule, allActive: false, targets: ['x1'], metric: 'cpa', op: '>', value: 1e5, accountIds: ['A'] })
  assert.deepEqual(pick.value.accountIds, [])
  const gone = validateRule({ ...okRule, metric: 'cpa', op: '>', value: 1e5, accountIds: ['Z'] }, { accounts })
  assert.ok(gone.warnings.some((w) => /Z/.test(w) && /không còn được quản lý/.test(w)))
})

test('cảnh báo khi tài khoản trong phạm vi chưa đặt mục tiêu; đã đặt đủ thì không cảnh báo', () => {
  const accounts = [{ id: 'A', name: 'Shop A' }, { id: 'B', name: 'Shop B' }]
  const rule = { ...okRule, conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 120 }] }
  const w1 = validateRule(rule, { accounts, accountTargets: { A: { cpa: 100000 } } }).warnings
  assert.ok(w1.some((w) => /Shop B/.test(w) && /CPA/.test(w)) && !w1.some((w) => /Shop A/.test(w)))
  assert.equal(validateRule(rule, { accounts, accountTargets: { A: { cpa: 1 }, B: { cpa: 2 } } }).warnings.filter((w) => /mục tiêu/.test(w)).length, 0)
  const scoped = validateRule({ ...rule, accountIds: ['A'] }, { accounts, accountTargets: { A: { cpa: 1 } } }) // chỉ xét tài khoản A
  assert.equal(scoped.warnings.filter((w) => /mục tiêu/.test(w)).length, 0)
})

test('cảnh báo rule mâu thuẫn chỉ so các rule 1 điều kiện số cụ thể', () => {
  const other = { id: 'o1', name: 'Tăng khi CPA thấp', enabled: true, metric: 'cpa', op: '<', value: 300000, action: 'increase', allActive: true }
  const mine = { ...okRule, metric: 'cpa', op: '>', value: 100000 }
  assert.ok(validateRule(mine, { rules: [other] }).warnings.some((w) => /mâu thuẫn/.test(w)))
  const multiOther = { ...other, conditions: [{ metric: 'cpa', op: '<', value: 300000 }, { metric: 'roas', op: '>', value: 2 }] }
  assert.ok(!validateRule(mine, { rules: [multiOther] }).warnings.some((w) => /mâu thuẫn/.test(w)), 'rule nhiều điều kiện không so được')
})

test('lưu cài đặt: phạm vi dừng khẩn và mục tiêu theo tài khoản', () => {
  assert.equal(validateSettings({ killScope: 'account' }).value.killScope, 'account')
  assert.ok(validateSettings({ killScope: 'khac' }).errors.killScope)
  const t = validateSettings({ accountTargets: { 123456: { cpa: '120000', roas: 2.345, dailySpendLimit: '' }, 999: { cpa: 0 } } })
  assert.deepEqual(t.value.accountTargets, { 123456: { cpa: 120000, roas: 2.35 } }) // trống/0 = chưa đặt → bỏ
  assert.ok(validateSettings({ accountTargets: { 1: { cpa: -5 } } }).errors.accountTargets)
  assert.ok(validateSettings({ accountTargets: { 1: { roas: 500 } } }).errors.accountTargets)
  assert.ok(validateSettings({ accountTargets: { 'ma sai': { cpa: 5 } } }).errors.accountTargets)
  assert.ok(validateSettings({ accountTargets: [] }).errors.accountTargets)
})

// =============== engine ===============
const objs = [camp('c1', 'A'), camp('c2', 'A'), camp('c3', 'A'), camp('c4', 'A')]
const metricsById = { c1: M({ spend: 1e6, results: 5, cpa: 200000, roas: 1 }), c2: M({ spend: 1e6, results: 5, cpa: 200000, roas: 2 }), c3: M({ spend: 1e6, results: 10, cpa: 100000, roas: 1 }), c4: M({ spend: 1e6, results: 10, cpa: 100000, roas: 2 }) }
const map = () => metricsById
const two = [{ metric: 'cpa', op: '>', value: 150000 }, { metric: 'roas', op: '<', value: 1.5 }]

test('VÀ: khớp khi mọi điều kiện đúng', () => {
  assert.deepEqual(hits(engine.evaluateRule({ ...base, conditions: two, match: 'all' }, objs, map)), ['c1'])
})

test('HOẶC: khớp khi có một điều kiện đúng', () => {
  assert.deepEqual(hits(engine.evaluateRule({ ...base, conditions: two, match: 'any' }, objs, map)).sort(), ['c1', 'c2', 'c3'])
})

test('rule cũ (metric/op/value) cho kết quả y như trước', () => {
  const legacy = engine.evaluateRule({ ...base, metric: 'cpa', op: '>', value: 150000 }, objs, map)
  const modern = engine.evaluateRule({ ...base, conditions: [{ metric: 'cpa', op: '>', value: 150000 }] }, objs, map)
  assert.deepEqual(hits(legacy), ['c1', 'c2'])
  assert.deepEqual(hits(modern), hits(legacy))
  assert.equal(legacy[0].value, 200000) // trường value cũ = giá trị điều kiện đầu
})

test('mỗi kết quả đánh giá ghi rõ từng điều kiện: giá trị thực, ngưỡng, đúng/sai', () => {
  const d = engine.evaluateRule({ ...base, conditions: two, match: 'all' }, objs, map).find((x) => x.obj.id === 'c2')
  assert.deepEqual(d.conds.map((c) => [c.metric, c.actual, c.threshold, c.hit]), [['cpa', 200000, 150000, true], ['roas', 2, 1.5, false]])
  assert.equal(d.status, 'nomatch')
})

test('so với mục tiêu: mỗi tài khoản dùng mục tiêu của chính nó', () => {
  const list = [camp('a1', 'A'), camp('b1', 'B')]
  S().settings.accountTargets = { A: { cpa: 100000 }, B: { cpa: 200000 } }
  const m = { a1: M({ spend: 1e6, results: 6, cpa: 150000, roas: 1 }), b1: M({ spend: 1e6, results: 6, cpa: 150000, roas: 1 }) }
  const rule = { ...base, conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 120 }] } // ngưỡng A = 120.000, B = 240.000
  const r = engine.evaluateRule(rule, list, () => m)
  assert.deepEqual(hits(r), ['a1'])
  assert.deepEqual(r.map((d) => d.conds[0].threshold), [120000, 240000])
})

test('thiếu mục tiêu: bỏ qua kèm lý do rõ ràng, không im lặng', () => {
  const list = [camp('a1', 'A'), camp('c1', 'C', { accountName: 'Shop C' })]
  S().settings.accountTargets = { A: { cpa: 100000, roas: 2 } }
  const m = { a1: M({ spend: 1e6, results: 6, cpa: 150000, roas: 3 }), c1: M({ spend: 1e6, results: 6, cpa: 150000, roas: 3 }) }
  const rule = { ...base, conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 100 }] }
  const c = engine.evaluateRule(rule, list, () => m).find((d) => d.obj.id === 'c1')
  assert.deepEqual([c.status, c.code, c.hit], ['skip', 'notarget', false])
  assert.match(c.reason, /Shop C.*chưa đặt mục tiêu CPA/)
  // VÀ: một điều kiện đã sai chắc chắn thì không cần mục tiêu → "không khớp" bình thường, không phải bỏ qua
  const and = { ...base, match: 'all', conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 100 }, { metric: 'roas', op: '<', value: 1 }] }
  assert.equal(engine.evaluateRule(and, list, () => m).find((d) => d.obj.id === 'c1').status, 'nomatch')
  // HOẶC: có một điều kiện đúng thì khớp dù điều kiện kia thiếu mục tiêu
  const or = { ...base, match: 'any', conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 100 }, { metric: 'roas', op: '>', value: 1 }] }
  assert.equal(engine.evaluateRule(or, list, () => m).find((d) => d.obj.id === 'c1').hit, true)
})

test('phạm vi tài khoản: chỉ xét camp của tài khoản đã chọn; rule chọn camp cụ thể không bị ảnh hưởng', () => {
  const list = [camp('a1', 'A'), camp('a2', 'A'), camp('b1', 'B')]
  const m = { a1: M({ spend: 9e6 }), a2: M({ spend: 9e6 }), b1: M({ spend: 9e6 }) }
  const r = { ...base, conditions: [{ metric: 'spend', op: '>', value: 1e6 }] }
  assert.deepEqual(hits(engine.evaluateRule(r, list, () => m)).sort(), ['a1', 'a2', 'b1']) // trống = mọi tài khoản
  assert.deepEqual(hits(engine.evaluateRule({ ...r, accountIds: ['B'] }, list, () => m)), ['b1'])
  assert.deepEqual(hits(engine.evaluateRule({ ...r, accountIds: ['A', 'B'] }, list, () => m)).sort(), ['a1', 'a2', 'b1'])
  assert.deepEqual(engine.evaluateRule({ ...r, accountIds: ['Z'] }, list, () => m), [])
  assert.deepEqual(hits(engine.evaluateRule({ ...r, allActive: false, targets: ['a1'], accountIds: ['B'] }, list, () => m)), ['a1'])
})

test('CTR/CPC/CPM tính giống derive() dùng ở bảng Tổng quan', () => {
  for (const m of [M({ spend: 90000, impressions: 10000, clicks: 200 }), M({ spend: 1234567, impressions: 987654, clicks: 4321 })]) {
    const d = derive(m)
    assert.ok(Math.abs(engine.metricValue(m, 'ctr') - d.ctr) < 1e-9)
    assert.ok(Math.abs(engine.metricValue(m, 'cpc') - d.cpc) < 1e-9)
    assert.ok(Math.abs(engine.metricValue(m, 'cpm') - d.cpm) < 1e-9)
  }
  // chưa có mẫu số: chi phí đã tiêu mà chưa có lượt nhấp là "đắt vô hạn", chưa tiêu thì 0; CTR/CPM chưa có hiển thị là 0
  assert.equal(engine.metricValue(M({ spend: 5e5, clicks: 0 }), 'cpc'), Infinity)
  assert.equal(engine.metricValue(M({ spend: 0, clicks: 0 }), 'cpc'), 0)
  assert.deepEqual([engine.metricValue(M({ impressions: 0, clicks: 0 }), 'ctr'), engine.metricValue(M({ impressions: 0 }), 'cpm')], [0, 0])
  assert.equal(engine.metricValue({ spend: 100 }, 'ctr'), 0) // số liệu cũ chưa có impressions/clicks không làm hỏng
})

test('runRules: rule nhiều điều kiện ghi nhật ký kèm từng điều kiện và tên rõ ràng', async () => {
  S().rules.push({ id: 'm1', name: 'Hai điều kiện', enabled: true, action: 'notify', cooldownHours: 24, allActive: true, level: 'campaign', range: 'today', minSpend: 0,
    conditions: [{ metric: 'spend', op: '>', value: 1 }, { metric: 'results', op: '>', value: -1 }], match: 'all' })
  await engine.runRules()
  const l = S().logs.find((x) => x.refId === 'm1')
  assert.ok(l, 'có nhật ký')
  assert.equal(l.condition.conditions.length, 2)
  assert.equal(l.condition.match, 'all')
  assert.ok(l.condition.conditions.every((c) => c.hit === true))
  assert.equal(l.condition.metric, 'spend') // điều kiện đầu vẫn ghi ở các trường cũ
  assert.match(l.source, /Rule: Hai điều kiện \[Chi tiêu .* · Kết quả /)
})

test('xem trước: mỗi mục kèm giá trị từng điều kiện', async () => {
  const pv = await engine.previewRule({ ...base, range: 'today', conditions: [{ metric: 'spend', op: '>', value: 1 }, { metric: 'results', op: '>', value: -1 }], match: 'all', action: 'notify' })
  assert.ok(pv.items.length > 0)
  assert.ok(pv.items.every((i) => Array.isArray(i.conds) && i.conds.length === 2))
})

// =============== dừng khẩn theo tài khoản ===============
test('dừng khẩn theo tài khoản: chỉ tắt camp của tài khoản vượt mức của nó', async () => {
  Object.assign(S().settings, { killSwitchEnabled: true, killScope: 'account', dailySpendLimit: 0, accountTargets: { mock_a: { dailySpendLimit: 1 }, mock_b: { dailySpendLimit: 1e12 } } })
  const o = await fb.listObjects(true)
  assert.ok(o.some((x) => x.accountId === 'mock_a' && x.effective === 'ACTIVE'))
  assert.equal(await engine.checkKillSwitch(o), true)
  const after = (await fb.listObjects(true)).filter((x) => x.level === 'campaign')
  assert.equal(after.filter((x) => x.accountId === 'mock_a' && x.effective === 'ACTIVE').length, 0)
  assert.ok(after.filter((x) => x.accountId === 'mock_b' && x.effective === 'ACTIVE').length > 0, 'tài khoản B không bị ảnh hưởng')
  const log = S().logs.find((l) => l.source === 'Dừng khẩn' && /Tài khoản/.test(l.name))
  assert.ok(log && /tài khoản này/.test(log.detail))
  assert.equal(await engine.checkKillSwitch(await fb.listObjects(true)), false) // A đã kích hoạt hôm nay
  assert.deepEqual(Object.keys(S().state.killFiredAcc), ['mock_a'])
})

test('dừng khẩn theo tài khoản: tài khoản chưa đặt mức riêng dùng mức chung; không có mức nào thì bỏ qua', async () => {
  Object.assign(S().settings, { killSwitchEnabled: true, killScope: 'account', dailySpendLimit: 1, accountTargets: { mock_b: { dailySpendLimit: 1e12 } } })
  assert.equal(await engine.checkKillSwitch(await fb.listObjects(true)), true) // A dùng mức chung 1 → tắt; B có mức riêng rất cao → giữ
  const camps = (await fb.listObjects(true)).filter((x) => x.level === 'campaign')
  assert.equal(camps.filter((x) => x.accountId === 'mock_a' && x.effective === 'ACTIVE').length, 0)
  assert.ok(camps.filter((x) => x.accountId === 'mock_b' && x.effective === 'ACTIVE').length > 0)
  // không mức riêng, không mức chung → không làm gì
  fb.resetMock(); fb.resetCache(); S().state.killFiredAcc = {}
  Object.assign(S().settings, { dailySpendLimit: 0, accountTargets: {} })
  assert.equal(await engine.checkKillSwitch(await fb.listObjects(true)), false)
})

test('dừng khẩn "tổng mọi tài khoản" (mặc định) không đổi: mức riêng theo tài khoản bị bỏ qua', async () => {
  Object.assign(S().settings, { killSwitchEnabled: true, killScope: 'total', dailySpendLimit: 1e12, accountTargets: { mock_a: { dailySpendLimit: 1 } } })
  assert.equal(await engine.checkKillSwitch(await fb.listObjects(true)), false) // tổng chưa tới 1e12, dù mức riêng của A là 1
  assert.equal(S().state.killFired, '')
})

test('dừng khẩn theo từng tài khoản: đủ điều kiện khi có mức riêng dù không có mức chung', () => {
  const cur = { killSwitchEnabled: false, killScope: 'account', dailySpendLimit: 0, accountTargets: {} }
  assert.ok(validateSettings({ killSwitchEnabled: true }, cur).errors.dailySpendLimit, 'chưa có mức nào thì báo lỗi')
  const ok = validateSettings({ killSwitchEnabled: true, accountTargets: { 111: { dailySpendLimit: 1000000 } } }, cur)
  assert.ok(ok.ok, JSON.stringify(ok.errors))
  const total = validateSettings({ killSwitchEnabled: true, killScope: 'total', accountTargets: { 111: { dailySpendLimit: 1000000 } } }, cur)
  assert.ok(total.errors.dailySpendLimit, 'phạm vi tổng vẫn cần mức chung')
})
