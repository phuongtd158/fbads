// Chỉ số phái sinh và hàng tổng cho bảng ở Tổng quan. Số liệu gốc của mỗi camp/nhóm QC (lib/fb.js metricsFrom):
//   { spend, impressions, clicks, results, cpa, roas }
// Mẫu số bằng 0 thì trả null (giao diện hiện "–"), không trả 0 hay Infinity.

export const EMPTY = { spend: 0, impressions: 0, clicks: 0, results: 0, revenue: 0, cpa: null, roas: null }

const div = (a, b) => (b > 0 ? a / b : null)

// CTR (%), CPC, CPM (chi phí / 1000 lượt hiển thị)
export function derive(m = EMPTY) {
  return { ...m, ctr: div(m.clicks * 100, m.impressions), cpc: div(m.spend, m.clicks), cpm: div(m.spend * 1000, m.impressions) }
}

// Tổng ngân sách hằng ngày của các chiến dịch đang chạy. Ngân sách có thể đặt ở 2 nơi:
//  - chiến dịch (CBO): lấy ngân sách của chiến dịch;
//  - nhóm QC (ABO, chiến dịch không có ngân sách riêng): cộng ngân sách các nhóm QC ĐANG CHẠY của chiến dịch đó.
// Ngân sách trọn đời (không có ngân sách ngày) không cộng được nên bỏ qua. camps/adsets: đối tượng { id, campaignId?, dailyBudget }.
export function runningBudget(camps, adsets, isRunning) {
  const byCamp = new Map()
  for (const a of adsets) { if (!byCamp.has(a.campaignId)) byCamp.set(a.campaignId, []); byCamp.get(a.campaignId).push(a) }
  let total = 0
  for (const c of camps) {
    if (c.dailyBudget != null) total += c.dailyBudget
    else for (const a of byCamp.get(c.id) || []) if (a.dailyBudget != null && isRunning(a)) total += a.dailyBudget
  }
  return total
}

// rows: [{ m: số liệu gốc, budget: ngân sách/ngày hoặc null }] → tổng + các chỉ số tính lại từ tổng (không lấy trung bình của các dòng).
// ROAS gộp = tổng doanh thu / tổng chi tiêu, với doanh thu mỗi dòng = roas × chi tiêu.
export function totals(rows) {
  const t = { spend: 0, impressions: 0, clicks: 0, results: 0, revenue: 0, budget: 0, budgetRows: 0, value: 0 }
  for (const { m, budget } of rows) {
    t.spend += m.spend; t.impressions += m.impressions; t.clicks += m.clicks; t.results += m.results; t.revenue += m.revenue || 0
    if (budget != null) { t.budget += budget; t.budgetRows++ }
    if (m.roas != null && m.spend > 0) t.value += m.roas * m.spend
  }
  const { value, ...rest } = t
  return { ...derive({ ...rest, cpa: null, roas: null }), cpa: div(t.spend, t.results), roas: div(value, t.spend) }
}
