// Rule tăng ngân sách theo bậc kết quả: có 1 kết quả +50%, kết quả thứ 2 +50%, từ kết quả thứ 3 cứ 3 giờ +50%.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateRule, MAX_STEPS } from '../shared/validate.mjs'
import { ruleName } from '../shared/names.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-ladder-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')
const S = () => store.get()

beforeEach(() => {
  const d = S()
  d.logs.length = 0; d.rules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, skipLearning: true, dailyChangeCapPct: 30, accountTargets: {} })
  fb.resetMock(); fb.resetCache()
})

const steps = [{ count: 1, mode: 'percent', value: 50 }, { count: 2, mode: 'percent', value: 50 }, { count: 3, mode: 'percent', value: 50, everyHours: 3 }]
const input = { name: 'Bậc', action: 'ladder', ladderMetric: 'results', steps, minSpend: 100000, maxBudget: 2000000, allActive: true, level: 'adset' }
const rule = () => ({ id: 'rl', ...validateRule(input).value })
const adset = (extra = {}) => ({ id: 'a1', name: 'A1', level: 'adset', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 200000, accountId: 'x', learning: true, ...extra })
const M = (spend, results) => () => ({ a1: { spend, results, impressions: 1000, clicks: 10 } })
const ev = (r, o, map) => engine.evaluateRule(r, [o], map)[0]
// làm như runRules sau khi tăng thành công: ghi bậc đã chạy, cập nhật ngân sách
const did = (d, ago = 0) => {
  const st = S().state; st.ladder = st.ladder || {}
  const date = new Intl.DateTimeFormat('en-CA', { timeZone: S().settings.timezone || 'Asia/Ho_Chi_Minh' }).format(new Date())
  st.ladder[`rl:${d.obj.id}`] = { date, step: d.ladder.step, at: Date.now() - ago }
  d.obj.dailyBudget = d.plan.next
}

test('kiểm tra dữ liệu: hợp lệ, không cần điều kiện, bắt buộc trần ngân sách', () => {
  const r = validateRule(input)
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  assert.deepEqual(r.value.conditions, [])
  assert.equal(r.value.range, 'today')
  assert.equal(r.value.cooldownHours, 0)
  assert.equal(r.value.includeLearning, true)
  assert.deepEqual(r.value.steps, steps)
  assert.ok(validateRule({ ...input, maxBudget: '' }).errors.maxBudget)
})

test('kiểm tra dữ liệu: bậc sai bị báo lỗi', () => {
  const bad = (st) => validateRule({ ...input, steps: st }).errors.steps
  assert.ok(bad([]), 'ít nhất 1 bậc')
  assert.ok(bad([{ count: 2, value: 50 }, { count: 2, value: 50 }]), 'số kết quả tăng dần')
  assert.ok(bad([{ count: 0, value: 50 }]), 'từ 1')
  assert.ok(bad([{ count: 1, value: 0 }]), 'mức tăng > 0')
  assert.ok(bad([{ count: 1, value: 150 }]), 'tối đa 100%')
  assert.ok(bad([{ count: 1, value: 50, everyHours: 0.5 }]), 'lặp lại từ 1 giờ')
  assert.ok(bad(Array.from({ length: MAX_STEPS + 1 }, (_, i) => ({ count: i + 1, value: 10 }))), `tối đa ${MAX_STEPS} bậc`)
  // chỉ bậc cuối được lặp lại: bậc giữa có everyHours thì bị bỏ đi
  assert.equal(validateRule({ ...input, steps: [{ count: 1, value: 10, everyHours: 3 }, { count: 2, value: 10 }] }).value.steps[0].everyHours, undefined)
})

test('tên tự đặt', () => {
  assert.equal(ruleName(rule()), 'Tăng NS nhóm QC theo bậc kết quả')
})

test('chưa đủ chi tối thiểu hoặc chưa có kết quả thì không tăng', () => {
  assert.equal(ev(rule(), adset(), M(90000, 1)).status, 'skip')
  const d = ev(rule(), adset(), M(120000, 0))
  assert.equal(d.hit, false)
})

test('đi qua từng bậc: +50% → +50% → bậc 3 lặp lại mỗi 3 giờ, không bị giới hạn 30%/ngày, kể cả đang học', () => {
  const o = adset(), r = rule()
  let d = ev(r, o, M(120000, 1))
  assert.equal(d.status, 'match'); assert.equal(d.plan.next, 300000); did(d)
  d = ev(r, o, M(130000, 1))
  assert.equal(d.status, 'skip'); assert.equal(d.code, 'ladderdone')
  d = ev(r, o, M(150000, 2))
  assert.equal(d.status, 'match'); assert.equal(d.plan.next, 450000); did(d)
  d = ev(r, o, M(180000, 3))
  assert.equal(d.status, 'match'); assert.equal(d.plan.next, 675000); did(d)
  d = ev(r, o, M(200000, 3))
  assert.equal(d.code, 'ladderwait', 'chưa đủ 3 giờ')
  did({ ...d, ladder: { step: 2 }, plan: { next: o.dailyBudget } }, 3 * 3600e3 + 1000)
  d = ev(r, o, M(250000, 4))
  assert.equal(d.status, 'match'); assert.equal(d.plan.next, 1012500)
})

test('nhảy nhiều bậc cùng lúc thì chỉ chạy bậc cao nhất; chạm trần thì dừng ở trần', () => {
  const o = adset({ dailyBudget: 1500000 })
  const d = ev(rule(), o, M(120000, 3))
  assert.equal(d.ladder.step, 2)
  assert.equal(d.plan.next, 2000000, 'chạm trần 2.000.000')
  did(d)
  assert.equal(ev(rule(), o, M(120000, 3)).code, 'ladderwait')
})

test('tắt "Áp dụng cả nhóm QC đang học" thì nhóm đang học bị bỏ qua như rule thường', () => {
  const d = ev({ ...rule(), includeLearning: false }, adset(), M(120000, 1))
  assert.equal(d.status, 'skip'); assert.equal(d.code, 'learning')
})

test('chạy thật: nhật ký ghi bậc, lần sau không tăng lại cùng bậc', async (t) => {
  S().rules.push({ ...rule(), level: 'campaign', minSpend: 0, maxBudget: 1e9, enabled: true, steps: [{ count: 0.5, mode: 'percent', value: 10 }] })
  // count 0.5: mọi camp có từ 1 kết quả đều đạt bậc 1 (dữ liệu giả)
  // số giả tăng theo giờ trong ngày (sáng sớm chưa camp nào có kết quả) → cố định 20h cho test không phụ thuộc giờ chạy
  const realHours = Date.prototype.getHours
  Date.prototype.getHours = function () { return 20 }
  t.after(() => { Date.prototype.getHours = realHours })
  await engine.runRules()
  const logs = S().logs.filter((l) => l.refId === 'rl' && !l.skipped)
  assert.ok(logs.length, 'có camp mock được tăng')
  assert.equal(logs[0].condition.ladder.step, 1)
  assert.match(logs[0].source, /Bậc 1: có \d+ kết quả/)
  await engine.runRules()
  assert.equal(S().logs.filter((l) => l.refId === 'rl' && !l.skipped).length, logs.length, 'không tăng lại bậc đã chạy hôm nay')
})
