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

// ----- Ngân sách đang chạy (vòng % ngân sách ở Tổng quan) -----
import { runningBudget } from '../shared/metrics.mjs'

test('ngân sách đang chạy: camp có ngân sách riêng (CBO) lấy của camp', () => {
  const camps = [{ id: 'c1', dailyBudget: 500000 }, { id: 'c2', dailyBudget: 300000 }]
  assert.equal(runningBudget(camps, [], () => true), 800000)
})

test('ngân sách đang chạy: camp không có ngân sách riêng (ABO) cộng các nhóm QC ĐANG CHẠY của camp đó', () => {
  const camps = [{ id: 'c1', dailyBudget: null }]
  const adsets = [
    { id: 'a1', campaignId: 'c1', dailyBudget: 100000, on: true },
    { id: 'a2', campaignId: 'c1', dailyBudget: 250000, on: true },
    { id: 'a3', campaignId: 'c1', dailyBudget: 900000, on: false }, // nhóm đang tắt: không tính
    { id: 'a4', campaignId: 'other', dailyBudget: 700000, on: true }, // của camp khác: không tính
  ]
  assert.equal(runningBudget(camps, adsets, (a) => a.on), 350000)
})

test('ngân sách đang chạy: không cộng đôi khi camp CBO có nhóm QC; ngân sách trọn đời (null) bị bỏ qua', () => {
  const camps = [{ id: 'c1', dailyBudget: 500000 }, { id: 'c2', dailyBudget: null }]
  const adsets = [
    { id: 'a1', campaignId: 'c1', dailyBudget: 999, on: true }, // camp c1 đã có ngân sách riêng → bỏ qua nhóm của nó
    { id: 'a2', campaignId: 'c2', dailyBudget: null, on: true }, // ngân sách trọn đời
    { id: 'a3', campaignId: 'c2', dailyBudget: 40000, on: true },
  ]
  assert.equal(runningBudget(camps, adsets, (a) => a.on), 540000)
  assert.equal(runningBudget([], adsets, () => true), 0)
  assert.equal(runningBudget([{ id: 'x', dailyBudget: null }], [], () => true), 0) // không có gì để cộng: 0, không phải NaN
})

test('doanh thu: cộng vào hàng tổng, dòng cũ chưa có doanh thu vẫn tính được', () => {
  const t = totals([
    { m: { spend: 100, impressions: 1000, clicks: 10, results: 2, revenue: 500, cpa: 50, roas: 5 }, budget: null },
    { m: { spend: 50, impressions: 500, clicks: 5, results: 1, revenue: 100, cpa: 50, roas: 2 }, budget: null },
    { m: { spend: 10, impressions: 100, clicks: 1, results: 0, cpa: null, roas: null }, budget: null }, // không có trường revenue
  ])
  assert.equal(t.revenue, 600)
  near(t.roas, 600 / 160, 'ROAS gộp = tổng doanh thu / tổng chi tiêu')
  assert.equal(totals([]).revenue, 0)
  assert.equal(EMPTY.revenue, 0)
})

test('cột kết quả phụ: cộng vào hàng tổng, chi phí/cuộc trò chuyện tính từ tổng', () => {
  const t = totals([
    { m: { spend: 100, impressions: 1000, clicks: 10, results: 2, conversations: 4, checkouts: 1, leads: 2, leadsOnMeta: 1, comments: 3, cpa: 50, roas: 5 }, budget: null },
    { m: { spend: 50, impressions: 500, clicks: 5, results: 1, conversations: 1, checkouts: 0, leads: 1, leadsOnMeta: 0, comments: 1, cpa: 50, roas: 2 }, budget: null },
    { m: { spend: 10, impressions: 100, clicks: 1, results: 0, cpa: null, roas: null }, budget: null }, // dòng cũ chưa có các cột này
  ])
  assert.deepEqual([t.conversations, t.checkouts, t.leads, t.leadsOnMeta, t.comments], [5, 1, 3, 1, 4])
  near(t.costPerConversation, 160 / 5, 'chi phí/cuộc trò chuyện gộp')
  assert.equal(totals([]).conversations, 0)
  assert.equal(derive(EMPTY).costPerConversation, null)
})
