// Chỉ số phái sinh và hàng tổng cho bảng ở Tổng quan. Số liệu gốc của mỗi camp/nhóm QC (lib/fb.js metricsFrom):
//   { spend, impressions, clicks, results, cpa, roas }
// Mẫu số bằng 0 thì trả null (giao diện hiện "–"), không trả 0 hay Infinity.

export const EMPTY = { spend: 0, impressions: 0, clicks: 0, results: 0, cpa: null, roas: null }

const div = (a, b) => (b > 0 ? a / b : null)

// CTR (%), CPC, CPM (chi phí / 1000 lượt hiển thị)
export function derive(m = EMPTY) {
  return { ...m, ctr: div(m.clicks * 100, m.impressions), cpc: div(m.spend, m.clicks), cpm: div(m.spend * 1000, m.impressions) }
}

// rows: [{ m: số liệu gốc, budget: ngân sách/ngày hoặc null }] → tổng + các chỉ số tính lại từ tổng (không lấy trung bình của các dòng).
// ROAS gộp = tổng doanh thu / tổng chi tiêu, với doanh thu mỗi dòng = roas × chi tiêu.
export function totals(rows) {
  const t = { spend: 0, impressions: 0, clicks: 0, results: 0, budget: 0, budgetRows: 0, value: 0 }
  for (const { m, budget } of rows) {
    t.spend += m.spend; t.impressions += m.impressions; t.clicks += m.clicks; t.results += m.results
    if (budget != null) { t.budget += budget; t.budgetRows++ }
    if (m.roas != null && m.spend > 0) t.value += m.roas * m.spend
  }
  const { value, ...rest } = t
  return { ...derive({ ...rest, cpa: null, roas: null }), cpa: div(t.spend, t.results), roas: div(value, t.spend) }
}
