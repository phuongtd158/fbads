import { test } from 'node:test'
import assert from 'node:assert/strict'
import { summarize, avgCpa, cpaTone, quickMatches } from '../src/shared/pick.mjs'

const it = (id, spend, results, revenue = 0, currency = 'VND') => ({ o: { id }, currency, m: { spend, results, revenue, cpa: results ? spend / results : null, roas: spend ? revenue / spend : null } })

test('tổng: CPA và ROAS tính lại từ tổng, không lấy trung bình các dòng', () => {
  const s = summarize([it('a', 100, 1, 300), it('b', 300, 3, 300)])
  assert.deepEqual(s, { spend: 400, results: 4, revenue: 600, cpa: 100, roas: 1.5 })
  assert.equal(summarize([]).cpa, null)
  assert.equal(summarize([it('a', 50, 0)]).roas, 0)
})

test('CPA trung bình: null khi chưa có kết quả hoặc lẫn loại tiền', () => {
  assert.equal(avgCpa([it('a', 100, 2), it('b', 200, 0)]), 150)
  assert.equal(avgCpa([it('a', 100, 0)]), null)
  assert.equal(avgCpa([it('a', 100, 1), it('b', 100, 1, 0, 'USD')]), null)
})

test('màu CPA so với trung bình', () => {
  assert.equal(cpaTone(90, 100), 'good')
  assert.equal(cpaTone(100, 100), 'good')
  assert.equal(cpaTone(130, 100), 'mid')
  assert.equal(cpaTone(131, 100), 'bad')
  assert.equal(cpaTone(null, 100), null)
  assert.equal(cpaTone(50, null), null)
})

test('chọn nhanh: ROAS ≥ 2, CPA thấp/cao hơn trung bình, có chi tiêu mà chưa có kết quả', () => {
  const items = [it('a', 100, 2, 300), it('b', 300, 2, 300), it('c', 80, 0), it('d', 0, 0)]
  // CPA trung bình = 480 / 4 = 120; a = 50, b = 150
  assert.deepEqual(quickMatches(items), { roas2: ['a'], cpaLow: ['a'], cpaHigh: ['b'], noResult: ['c'] })
  const mixed = [it('a', 100, 2, 300), it('b', 100, 1, 0, 'USD')]
  assert.deepEqual(quickMatches(mixed).cpaLow, [])
})
