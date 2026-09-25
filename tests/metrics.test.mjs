import { test } from 'node:test'
import assert from 'node:assert/strict'
import { derive, totals, EMPTY } from '../shared/metrics.mjs'

const near = (a, b, msg) => assert.ok(Math.abs(a - b) < 1e-9, `${msg}: ${a} ≠ ${b}`)

test('chỉ số phái sinh: CTR, CPC, CPM', () => {
  const d = derive({ spend: 90000, impressions: 10000, clicks: 200, results: 3, cpa: 30000, roas: 2 })
  near(d.ctr, 2, 'CTR (%)'); near(d.cpc, 450, 'CPC'); near(d.cpm, 9000, 'CPM')
  assert.equal(d.spend, 90000) // giữ nguyên số liệu gốc
})

test('mẫu số bằng 0 thì trả null, không phải 0 hay Infinity', () => {
  const d = derive(EMPTY)
  assert.deepEqual([d.ctr, d.cpc, d.cpm], [null, null, null])
  const noClick = derive({ spend: 1000, impressions: 500, clicks: 0, results: 0, cpa: null, roas: null })
  assert.equal(noClick.cpc, null)
  near(noClick.cpm, 2000, 'CPM')
  assert.deepEqual(Object.values(derive()).filter((v) => v === Infinity || Number.isNaN(v)), [])
})

test('hàng tổng: cộng số liệu rồi tính lại chỉ số từ tổng, không lấy trung bình các dòng', () => {
  const rows = [
    { m: { spend: 1000, impressions: 100000, clicks: 1000, results: 10, cpa: 100, roas: 3 }, budget: 2000 },
    { m: { spend: 3000, impressions: 100000, clicks: 100, results: 1, cpa: 3000, roas: 1 }, budget: null },
  ]
  const t = totals(rows)
  assert.deepEqual([t.spend, t.impressions, t.clicks, t.results], [4000, 200000, 1100, 11])
  near(t.cpa, 4000 / 11, 'CPA gộp')          // không phải (100 + 3000) / 2
  near(t.roas, (3 * 1000 + 1 * 3000) / 4000, 'ROAS gộp') // theo chi tiêu: 1,5 chứ không phải trung bình 2
  near(t.ctr, 0.55, 'CTR gộp'); near(t.cpc, 4000 / 1100, 'CPC gộp'); near(t.cpm, 20, 'CPM gộp')
  assert.deepEqual([t.budget, t.budgetRows], [2000, 1]) // mục không có ngân sách riêng (CBO) không cộng
})

test('hàng tổng của danh sách rỗng hoặc chưa có số liệu', () => {
  const t = totals([])
  assert.deepEqual([t.spend, t.results, t.budget, t.cpa, t.roas, t.ctr, t.cpc, t.cpm], [0, 0, 0, null, null, null, null, null])
  const z = totals([{ m: EMPTY, budget: null }])
  assert.deepEqual([z.cpa, z.roas], [null, null])
})

test('ROAS gộp bỏ qua dòng chưa chi tiêu', () => {
  const t = totals([
    { m: { spend: 0, impressions: 0, clicks: 0, results: 0, cpa: null, roas: null }, budget: 500 },
    { m: { spend: 200, impressions: 1000, clicks: 10, results: 1, cpa: 200, roas: 4 }, budget: 500 },
  ])
  near(t.roas, 4, 'ROAS')
  assert.equal(t.budget, 1000)
})
