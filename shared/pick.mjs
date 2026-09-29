// Hỗ trợ chọn camp / nhóm QC theo KẾT QUẢ trong hộp thoại "Đổi ngân sách hàng loạt":
// cộng số liệu của các mục, so CPA từng mục với CPA trung bình của danh sách, và các nút "Chọn nhanh".
// items: [{ m: số liệu (spend, results, revenue, cpa, roas), currency }]. Chỉ tính toán, không gọi API.

const div = (a, b) => (b > 0 ? a / b : null)

// Tổng của nhiều mục; CPA và ROAS tính lại từ tổng (không lấy trung bình các dòng)
export function summarize(items) {
  let spend = 0, results = 0, revenue = 0
  for (const { m } of items) { spend += m.spend || 0; results += m.results || 0; revenue += m.revenue || 0 }
  return { spend, results, revenue, cpa: div(spend, results), roas: div(revenue, spend) }
}

// Khác loại tiền (VND + USD) thì không so CPA với nhau được
export const oneCurrency = (items) => new Set(items.map((i) => i.currency || '')).size <= 1

// CPA trung bình của danh sách để so sánh; null khi chưa có kết quả nào hoặc lẫn loại tiền
export const avgCpa = (items) => (oneCurrency(items) ? summarize(items).cpa : null)

// So CPA với trung bình: 'good' (từ trung bình trở xuống), 'mid' (cao hơn tới 30%), 'bad' (cao hơn 30%)
export function cpaTone(cpa, avg) {
  if (cpa == null || avg == null) return null
  return cpa <= avg ? 'good' : cpa <= avg * 1.3 ? 'mid' : 'bad'
}

// Các nút "Chọn nhanh". test(m, avg) → mục có khớp không
export const QUICK = [
  { key: 'roas2', label: 'ROAS từ 2 trở lên', short: 'ROAS ≥ 2', test: (m) => m.spend > 0 && m.roas != null && m.roas >= 2 },
  { key: 'cpaLow', label: 'CPA thấp hơn trung bình', short: 'CPA thấp', test: (m, avg) => avg != null && m.cpa != null && m.cpa < avg },
  { key: 'cpaHigh', label: 'CPA cao hơn trung bình', short: 'CPA cao', test: (m, avg) => avg != null && m.cpa != null && m.cpa > avg },
  { key: 'noResult', label: 'Có chi tiêu, chưa có kết quả', short: 'Chưa có KQ', test: (m) => m.spend > 0 && !m.results },
]

// Id các mục khớp từng nút chọn nhanh: { roas2: [id…], … }. items: [{ o: { id }, m }]
export function quickMatches(items, avg = avgCpa(items)) {
  const out = {}
  for (const q of QUICK) out[q.key] = items.filter((i) => q.test(i.m, avg)).map((i) => i.o.id)
  return out
}
