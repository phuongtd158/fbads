import { test } from 'node:test'
import assert from 'node:assert/strict'
import { daysText, daysPreset, timesText, budgetText, scheduleName, describeSchedule, ruleName } from '../shared/names.mjs'

test('ngày chạy: hằng ngày, T2–T6, cuối tuần, danh sách', () => {
  assert.equal(daysText([0, 1, 2, 3, 4, 5, 6]), 'Hằng ngày')
  assert.equal(daysText([5, 1, 2, 3, 4]), 'T2–T6')
  assert.equal(daysText([0, 6]), 'Cuối tuần')
  assert.equal(daysText([0, 2, 4]), 'T3, T5, CN')
  assert.equal(daysText([]), '')
  assert.equal(daysPreset([1, 2, 3, 4, 5]), 'weekdays')
  assert.equal(daysPreset([1, 3]), '')
})

test('giờ chạy: sắp xếp, bỏ trùng, quá 3 giờ thì rút gọn', () => {
  assert.equal(timesText(['12:00', '06:00', '06:00']), '06:00, 12:00')
  assert.equal(timesText(['06:00', '09:00', '12:00', '15:00', '18:00']), '06:00, 09:00, 12:00 +2 giờ')
})

test('tên lịch tự đặt theo hành động', () => {
  const days = [0, 1, 2, 3, 4, 5, 6]
  assert.equal(scheduleName({ action: 'on', times: ['12:00', '06:00'], days }), 'Bật camp 06:00, 12:00 · Hằng ngày')
  assert.equal(scheduleName({ action: 'off', times: ['23:00'], days: [1, 2, 3, 4, 5] }), 'Tắt camp 23:00 · T2–T6')
  assert.equal(scheduleName({ action: 'window', window: { on: '06:00', off: '23:00' }, days: [6, 0] }), 'Bật 06:00 → tắt 23:00 · Cuối tuần')
  assert.equal(scheduleName({ action: 'budget', mode: 'percent', value: 20, times: ['06:00'], days }), 'Ngân sách +20% lúc 06:00 · Hằng ngày')
  assert.equal(scheduleName({ action: 'budget', mode: 'set', value: 500000, times: ['06:00'], days }), 'Ngân sách = 500.000 lúc 06:00 · Hằng ngày')
  assert.equal(scheduleName({ action: 'budget', mode: 'add', value: -50000, times: ['06:00'], days }), 'Ngân sách -50.000 lúc 06:00 · Hằng ngày')
  assert.equal(scheduleName({ action: 'on', time: '07:30', days }), 'Bật camp 07:30 · Hằng ngày') // lịch cũ chỉ có `time`
  assert.equal(budgetText({ mode: 'percent', value: 'abc' }), '')
})

test('câu tóm tắt lịch', () => {
  const days = [0, 1, 2, 3, 4, 5, 6]
  assert.equal(describeSchedule({ action: 'on', times: ['06:00', '12:00'], days }, { count: 3 }), 'Bật 3 chiến dịch lúc 06:00 và 12:00, hằng ngày.')
  assert.equal(describeSchedule({ action: 'window', window: { on: '06:00', off: '23:00' }, days: [1, 2, 3, 4, 5] }, { count: 5, auto: true }), 'Bật 5 chiến dịch đang khớp điều kiện lúc 06:00 và tắt lúc 23:00, từ thứ 2 đến thứ 6.')
  assert.equal(describeSchedule({ action: 'off', times: [], days: [] }, { count: 0, unit: 'nhóm QC' }), 'Tắt các nhóm QC bạn chọn lúc …, chưa chọn ngày.')
})

test('tên rule tự đặt', () => {
  assert.equal(ruleName({ action: 'pause', conditions: [{ metric: 'cpa', op: '>', value: 150000 }] }), 'Tắt camp CPA > 150.000')
  assert.equal(ruleName({ action: 'increase', level: 'adset', match: 'all', conditions: [{ metric: 'roas', op: '>', value: 2 }, { metric: 'results', op: '>', value: 3 }] }), 'Tăng NS nhóm QC ROAS > 2 & Kết quả > 3')
  assert.equal(ruleName({ action: 'notify', match: 'any', conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 120 }, { metric: 'cpa', op: '>', vs: 'range', factor: 130, compareRange: 'last_7d' }] }), 'Báo khi CPA > 120% mục tiêu hoặc CPA > 130% 7 ngày')
  assert.equal(ruleName({ action: 'pause', metric: 'spend', op: '>', value: 300000 }), 'Tắt camp Chi tiêu > 300.000') // rule cũ
  const long = ruleName({ action: 'pause', conditions: Array.from({ length: 5 }, () => ({ metric: 'costPerMessage', op: '>', value: 123456789 })) })
  assert.ok(long.length <= 80 && long.endsWith('…'))
})
