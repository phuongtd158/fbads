// Chọn theo điều kiện + đổi ngân sách hàng loạt (shared/bulk.mjs): dùng cho hộp thoại hàng loạt và lịch "Theo điều kiện".
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseMoney, readForm, planBulk, matchFilter, describeFilter } from '../shared/bulk.mjs'

test('đọc số tiền kiểu Việt', () => {
  assert.equal(parseMoney('500000'), 500000)
  assert.equal(parseMoney('500.000'), 500000)
  assert.equal(parseMoney('1.500.000 đ'), 1500000)
  assert.equal(parseMoney('100k'), 100000)
  assert.equal(parseMoney('1,5tr'), 1500000)
  assert.equal(parseMoney('2 triệu'), 2000000)
  assert.equal(parseMoney('-50k'), -50000)
  assert.equal(parseMoney(300000), 300000)
  assert.ok(Number.isNaN(parseMoney('abc')))
  assert.ok(Number.isNaN(parseMoney('')))
})

const objs = [
  { id: 'c1', level: 'campaign', name: 'Camp', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 30000 },
  { id: 'a1', level: 'adset', campaignId: 'c1', name: 'Nhóm A', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 80000 },
  { id: 'a2', level: 'adset', campaignId: 'c1', name: 'Nhóm B', status: 'PAUSED', effective: 'PAUSED', dailyBudget: 100000 },
  { id: 'a3', level: 'adset', campaignId: 'c1', name: 'Nhóm C', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 50000 },
  { id: 'a4', level: 'adset', campaignId: 'c1', name: 'Nhóm CBO', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: null },
  { id: 'a5', level: 'adset', campaignId: 'c1', name: 'Nhóm cũ', status: 'PAUSED', effective: 'ARCHIVED', dailyBudget: 20000 },
]
const plan = (f) => { const r = readForm({ level: 'adset', name: '', onlyRunning: false, ...f }); assert.deepEqual(r.errors, {}); return planBulk(objs, r) }

test('ví dụ: mọi nhóm QC dưới 100k → đặt 500k', () => {
  const { items } = plan({ op: 'lt', x: '100k', mode: 'set', value: '500k' })
  assert.deepEqual(items.map((i) => [i.o.id, i.from, i.to]), [['a1', 80000, 500000], ['a3', 50000, 500000]]) // không lấy nhóm = 100k, CBO, đã lưu trữ, camp
})

test('chỉ nhóm đang chạy (theo cột Phân phối), lọc theo tên, khoảng', () => {
  assert.deepEqual(plan({ op: 'lte', x: '100000', mode: 'set', value: '500000', onlyRunning: true }).items.map((i) => i.o.id), ['a1', 'a3'])
  assert.deepEqual(plan({ op: 'any', mode: 'set', value: '500k', name: 'nhóm b' }).items.map((i) => i.o.id), ['a2'])
  assert.deepEqual(plan({ op: 'between', x: '60k', y: '100k', mode: 'set', value: '500k' }).items.map((i) => i.o.id), ['a1', 'a2'])
})

test('tăng/giảm theo % và cộng/trừ tiền; bỏ qua mục không đổi hoặc về ≤ 0', () => {
  assert.deepEqual(plan({ op: 'any', mode: 'percent', value: '20' }).items.map((i) => i.to), [96000, 120000, 60000])
  assert.deepEqual(plan({ op: 'any', mode: 'add', value: '-50k' }).items.map((i) => [i.o.id, i.to]), [['a1', 30000], ['a2', 50000]])
  const r = plan({ op: 'any', mode: 'set', value: '100k' })
  assert.equal(r.skipped.same, 1); assert.equal(r.skipped.noBudget, 1)
})

test('lọc theo điều kiện cho bật/tắt (không cần ngân sách) và mô tả điều kiện', () => {
  assert.deepEqual(matchFilter(objs, { level: 'adset', op: 'any', name: 'nhóm' }).map((o) => o.id), ['a1', 'a2', 'a3', 'a4'])
  assert.deepEqual(matchFilter(objs, { level: 'campaign', op: 'any' }).map((o) => o.id), ['c1'])
  assert.equal(describeFilter({ level: 'adset', op: 'lt', x: 100000, name: 'Phương', onlyRunning: true }), 'Nhóm QC ngân sách dưới 100.000 · tên chứa “Phương” · đang chạy')
  assert.equal(describeFilter({ level: 'campaign', op: 'between', x: 300000, y: 100000 }), 'Chiến dịch ngân sách từ 100.000 đến 300.000')
  assert.equal(describeFilter({ level: 'adset', op: 'any' }), 'Mọi nhóm QC')
})

test('form sai thì báo lỗi', () => {
  assert.ok(readForm({ op: 'lt', x: '', mode: 'set', value: '500k' }).errors.x)
  assert.ok(readForm({ op: 'between', x: '10k', y: '', mode: 'set', value: '500k' }).errors.y)
  assert.ok(readForm({ op: 'any', mode: 'set', value: '0' }).errors.value)
  assert.ok(readForm({ op: 'any', mode: 'percent', value: '-95' }).errors.value)
  assert.ok(readForm({ op: 'any', mode: 'add', value: 'x' }).errors.value)
})
