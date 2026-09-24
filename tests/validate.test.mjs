import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validateSchedule, validateRule, validateBudget, validateSettings, validatePassword, isTimezone, isTime } from '../shared/validate.mjs'

const objs = [
  { id: 'c1', name: 'Camp A', dailyBudget: 500000 },
  { id: 'c2', name: 'Camp B (CBO)', dailyBudget: null },
]
const okSchedule = { name: 'Bật sáng', action: 'on', time: '06:00', days: [1, 2, 3], targets: ['c1'] }
const okRule = { name: 'Tắt CPA cao', metric: 'cpa', op: '>', value: 150000, minSpend: 100000, action: 'pause', cooldownHours: 24, allActive: true }

test('helpers', () => {
  assert.ok(isTime('06:00') && isTime('23:59') && !isTime('24:00') && !isTime('6:00') && !isTime(''))
  assert.ok(isTimezone('Asia/Ho_Chi_Minh') && !isTimezone('Mars/Base') && !isTimezone(''))
})

test('schedule hợp lệ', () => {
  const r = validateSchedule(okSchedule, { objs })
  assert.equal(r.ok, true)
  assert.deepEqual(r.value.days, [1, 2, 3])
})

test('schedule: thiếu giờ / ngày / đích', () => {
  const r = validateSchedule({ action: 'on', time: '25:00', days: [], targets: [] }, { objs })
  assert.ok(r.errors.time && r.errors.days && r.errors.targets)
})

test('schedule ngân sách: % không hợp lệ', () => {
  const base = { ...okSchedule, action: 'budget', mode: 'percent' }
  assert.ok(validateSchedule({ ...base, value: 0 }, { objs }).errors.value)
  assert.ok(validateSchedule({ ...base, value: -100 }, { objs }).errors.value)
  assert.ok(validateSchedule({ ...base, value: -95 }, { objs }).errors.value)
  assert.ok(validateSchedule({ ...base, value: 500 }, { objs }).errors.value)
  assert.equal(validateSchedule({ ...base, value: -30 }, { objs }).ok, true)
  assert.ok(validateSchedule({ ...base, value: 60 }, { objs }).warnings.length > 0)
})

test('schedule ngân sách: số tiền cố định phải > 0', () => {
  const base = { ...okSchedule, action: 'budget', mode: 'set' }
  assert.ok(validateSchedule({ ...base, value: 0 }, { objs }).errors.value)
  assert.ok(validateSchedule({ ...base, value: -5 }, { objs }).errors.value)
  assert.equal(validateSchedule({ ...base, value: 300000 }, { objs }).ok, true)
})

test('schedule ngân sách: chặn mục CBO không có ngân sách riêng', () => {
  const r = validateSchedule({ ...okSchedule, action: 'budget', mode: 'percent', value: 20, targets: ['c1', 'c2'] }, { objs })
  assert.ok(r.errors.targets && r.errors.targets.includes('CBO'))
  assert.equal(validateSchedule({ ...okSchedule, action: 'on', targets: ['c2'] }, { objs }).ok, true) // bật/tắt thì được
})

test('schedule: đích không tồn tại', () => {
  assert.ok(validateSchedule({ ...okSchedule, targets: ['zzz'] }, { objs }).errors.targets)
})

test('schedule: xung đột bật/tắt cùng giờ, trùng lặp', () => {
  const existing = [{ id: 's1', name: 'Tắt sáng', action: 'off', time: '06:00', days: [1, 2], targets: ['c1'], enabled: true }]
  assert.ok(validateSchedule(okSchedule, { objs, schedules: existing }).errors.conflict)
  const same = [{ id: 's2', ...okSchedule, enabled: true }]
  assert.ok(validateSchedule(okSchedule, { objs, schedules: same }).errors.conflict.includes('giống hệt'))
  assert.equal(validateSchedule({ ...okSchedule, id: 's2' }, { objs, schedules: same }).ok, true) // sửa chính nó thì không tự xung đột
  const off = [{ id: 's1', name: 'Tắt sáng', action: 'off', time: '06:00', days: [1], targets: ['c1'], enabled: false }]
  assert.equal(validateSchedule(okSchedule, { objs, schedules: off }).ok, true) // lịch đang tắt thì bỏ qua
})

test('rule hợp lệ', () => {
  assert.equal(validateRule(okRule, { objs }).ok, true)
})

test('rule: cần chi tiêu tối thiểu với CPA/ROAS/kết quả', () => {
  assert.ok(validateRule({ ...okRule, minSpend: 0 }).errors.minSpend)
  assert.equal(validateRule({ ...okRule, metric: 'spend', value: 500000, minSpend: 0 }).ok, true)
})

test('rule: ngưỡng phi lý', () => {
  assert.ok(validateRule({ ...okRule, value: 0 }).errors.value)
  assert.ok(validateRule({ ...okRule, value: -1 }).errors.value)
  assert.ok(validateRule({ ...okRule, metric: 'roas', op: '>', value: 5000 }).errors.value)
})

test('rule đổi ngân sách: % và thời gian nghỉ', () => {
  const inc = { ...okRule, metric: 'roas', op: '>', value: 3, action: 'increase', pct: 20, maxBudget: 2000000 }
  assert.equal(validateRule(inc).ok, true)
  assert.ok(validateRule({ ...inc, pct: 0 }).errors.pct)
  assert.ok(validateRule({ ...inc, pct: 150 }).errors.pct)
  assert.ok(validateRule({ ...inc, cooldownHours: 0 }).errors.cooldownHours)
  const dec = { ...inc, action: 'decrease', minBudget: 100000, maxBudget: 0 }
  assert.ok(validateRule({ ...dec, pct: 100 }).errors.pct)
  assert.equal(validateRule({ ...dec, pct: 20 }).ok, true)
  assert.ok(validateRule({ ...inc, minBudget: 3000000 }).errors.maxBudget) // trần < sàn
  assert.ok(validateRule({ ...inc, maxBudget: 0 }).warnings.length > 0) // không đặt trần → cảnh báo
})

test('rule: khung giờ', () => {
  assert.ok(validateRule({ ...okRule, from: '22:00', to: '06:00' }).errors.window)
  assert.ok(validateRule({ ...okRule, from: '08:00' }).errors.window)
  assert.equal(validateRule({ ...okRule, from: '08:00', to: '22:00' }).ok, true)
})

test('rule: chọn camp cụ thể phải có đích', () => {
  assert.ok(validateRule({ ...okRule, allActive: false, targets: [] }, { objs }).errors.targets)
  assert.equal(validateRule({ ...okRule, allActive: false, targets: ['c1'] }, { objs }).ok, true)
})

test('rule: cảnh báo mâu thuẫn tăng/giảm chồng lấn', () => {
  const other = [{ id: 'r1', name: 'Tăng khi ROAS tốt', metric: 'roas', op: '>', value: 2, action: 'increase', allActive: true, enabled: true }]
  const r = validateRule({ ...okRule, metric: 'roas', op: '>', value: 3, action: 'pause', minSpend: 200000 }, { objs, rules: other })
  assert.ok(r.warnings.some((w) => w.includes('mâu thuẫn')))
  const r2 = validateRule({ ...okRule, metric: 'roas', op: '<', value: 1.5, action: 'pause', minSpend: 200000 }, { objs, rules: other })
  assert.ok(!r2.warnings.some((w) => w.includes('mâu thuẫn'))) // vùng giá trị không chồng lấn
})

test('ngân sách thủ công', () => {
  assert.equal(validateBudget('abc', 100000).ok, false)
  assert.equal(validateBudget(0, 100000).ok, false)
  assert.equal(validateBudget(-5, 100000).ok, false)
  assert.equal(validateBudget(1e12, 100000).ok, false)
  const a = validateBudget(120000, 100000)
  assert.ok(a.ok && !a.confirm && a.value === 120000)
  assert.ok(validateBudget(300000, 100000).confirm.includes('tăng'))
  assert.ok(validateBudget(40000, 100000).confirm.includes('giảm'))
})

test('cài đặt', () => {
  assert.ok(validateSettings({ timezone: 'Mars/Base' }).errors.timezone)
  assert.ok(validateSettings({ ruleIntervalMin: 1 }).errors.ruleIntervalMin)
  assert.ok(validateSettings({ ruleIntervalMin: 10.5 }).errors.ruleIntervalMin)
  assert.equal(validateSettings({ ruleIntervalMin: '15' }).value.ruleIntervalMin, 15)
  assert.ok(validateSettings({ reportTime: '8h' }).errors.reportTime)
  assert.ok(validateSettings({ telegramChatId: 'abc' }).errors.telegramChatId)
  assert.ok(validateSettings({ telegramToken: 'nope' }).errors.telegramToken)
  assert.ok(validateSettings({ adAccountId: 'abc' }).errors.adAccountId)
  assert.equal(validateSettings({ adAccountId: 'act_123456789' }).value.adAccountId, '123456789')
  assert.ok(validateSettings({ accessToken: 'short' }).errors.accessToken)
  assert.ok(validateSettings({ accessToken: 'EAA has space in it 1234567890' }).errors.accessToken)
})

test('cài đặt: dùng dữ liệu thật cần kết nối trước', () => {
  assert.ok(validateSettings({ mock: false, dryRun: true }, { mock: true, accessToken: '', adAccountId: '' }).errors.mock)
  assert.equal(validateSettings({ mock: false, dryRun: true }, { mock: true, accessToken: 'x'.repeat(30), adAccountId: '12345678' }).ok, true)
  assert.equal(validateSettings({ mock: true, dryRun: true }, { mock: false, accessToken: '', adAccountId: '' }).ok, true)
})

test('mật khẩu', () => {
  assert.ok(validatePassword('short').errors.newPassword)
  assert.ok(validatePassword('12345678').errors.newPassword)
  assert.ok(validatePassword('87654321').errors.newPassword) // chỉ gồm số
  assert.ok(validatePassword('password').errors.newPassword)
  assert.ok(validatePassword('matkhau-cu-1', 'matkhau-cu-1').errors.newPassword)
  assert.equal(validatePassword('mat-khau-moi-99', 'matkhau-cu-1').ok, true)
})

test('rule: khoảng thời gian', () => {
  assert.equal(validateRule({ ...okRule, range: 'last_3d' }, { objs }).ok, true)
  assert.equal(validateRule({ ...okRule, range: 'last_3d' }, { objs }).value.range, 'last_3d')
  assert.ok(validateRule({ ...okRule, range: 'last_year' }).errors.range)
  assert.equal(validateRule({ ...okRule }).value.range, 'today') // không chọn thì mặc định hôm nay
  // tắt/giảm chỉ dựa vào hôm nay → cảnh báo; dùng 3 ngày thì không
  assert.ok(validateRule({ ...okRule, range: 'today' }).warnings.some((w) => w.includes('hôm nay')))
  assert.ok(!validateRule({ ...okRule, range: 'last_3d' }).warnings.some((w) => w.includes('hôm nay')))
  // chi tiêu thì hôm nay là tự nhiên, không cảnh báo
  assert.ok(!validateRule({ ...okRule, metric: 'spend', value: 500000, minSpend: 0, range: 'today' }).warnings.some((w) => w.includes('hôm nay')))
})

test('rule: chỉ thông báo', () => {
  const n = { ...okRule, action: 'notify', cooldownHours: 12 }
  assert.equal(validateRule(n).ok, true)
  assert.equal(validateRule(n).value.pct, 0)
  assert.ok(validateRule({ ...n, cooldownHours: 0 }).errors.cooldownHours) // tránh thông báo lặp mỗi chu kỳ
  assert.equal(validateRule({ ...n, minSpend: 0 }).ok, false) // vẫn cần chi tiêu tối thiểu
  // rule chỉ thông báo không gây cảnh báo mâu thuẫn
  const other = [{ id: 'r1', name: 'Tăng', metric: 'cpa', op: '>', value: 100000, action: 'increase', allActive: true, enabled: true }]
  assert.ok(!validateRule(n, { rules: other }).warnings.some((w) => w.includes('mâu thuẫn')))
})

test('cài đặt bảo vệ ngân sách', () => {
  assert.ok(validateSettings({ dailyChangeCapPct: 2 }).errors.dailyChangeCapPct)
  assert.ok(validateSettings({ dailyChangeCapPct: 500 }).errors.dailyChangeCapPct)
  assert.ok(validateSettings({ dailyChangeCapPct: 12.5 }).errors.dailyChangeCapPct)
  assert.equal(validateSettings({ dailyChangeCapPct: '30' }).value.dailyChangeCapPct, 30)
  assert.equal(validateSettings({ skipLearning: 0 }).value.skipLearning, false)
  assert.ok(validateSettings({ dailySpendLimit: -1 }).errors.dailySpendLimit)
  // bật dừng khẩn thì phải có mức > 0
  assert.ok(validateSettings({ killSwitchEnabled: true, dailySpendLimit: 0 }).errors.dailySpendLimit)
  assert.ok(validateSettings({ killSwitchEnabled: true }, { dailySpendLimit: 0 }).errors.dailySpendLimit)
  assert.equal(validateSettings({ killSwitchEnabled: true, dailySpendLimit: 3000000 }).ok, true)
  assert.equal(validateSettings({ killSwitchEnabled: false, dailySpendLimit: 0 }).ok, true)
})
