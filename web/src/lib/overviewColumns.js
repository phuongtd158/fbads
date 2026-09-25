// Các cột của bảng ở Tổng quan (bật/tắt được, giống mục "Cột" của Ads Manager).
// Mỗi dòng của bảng là { o: camp/nhóm QC, m: số liệu đã kèm CTR/CPC/CPM (shared/metrics.mjs derive) }.
import { fmt, fmtDec } from './format'
import { decimalsOf } from './accounts'

// money: cột tiền (hiện đúng số lẻ theo loại tiền của tài khoản); first: chiều sắp xếp khi bấm lần đầu (mặc định giảm dần)
export const COLUMNS = [
  { key: 'budget', label: 'Ngân sách/ngày', short: 'Ngân sách', menu: 'Ngân sách hằng ngày', tip: 'budget', min: 118 },
  { key: 'spend', label: 'Chi tiêu', money: true, min: 98 },
  { key: 'results', label: 'Kết quả', tip: 'results', min: 78 },
  { key: 'cpa', label: 'CPA', money: true, first: 'asc', tip: 'cpa', min: 92 },
  { key: 'roas', label: 'ROAS', tip: 'roas', min: 78 },
  { key: 'impressions', label: 'Lượt hiển thị', min: 112 },
  { key: 'clicks', label: 'Lượt nhấp', min: 92 },
  { key: 'ctr', label: 'CTR', menu: 'CTR (tỉ lệ nhấp)', min: 76 },
  { key: 'cpc', label: 'CPC', menu: 'CPC (chi phí mỗi nhấp)', money: true, first: 'asc', min: 92 },
  { key: 'cpm', label: 'CPM', menu: 'CPM (chi phí 1000 hiển thị)', money: true, first: 'asc', min: 94 },
]
export const COLUMN_KEYS = COLUMNS.map((c) => c.key)
export const DEFAULT_COLUMNS = ['budget', 'spend', 'results', 'cpa', 'roas']
export const colOf = (key) => COLUMNS.find((c) => c.key === key)

// Danh sách cột đã lưu → hợp lệ, đúng thứ tự chuẩn, luôn có ít nhất 1 cột
export function cleanColumns(list) {
  const want = new Set(Array.isArray(list) ? list : [])
  const out = COLUMN_KEYS.filter((k) => want.has(k))
  return out.length ? out : [...DEFAULT_COLUMNS]
}

export const money = (n, cur) => (n == null || Number.isNaN(n) ? '–' : decimalsOf(cur) ? fmtDec(n, 2) : fmt(n))

// Giá trị dùng để sắp xếp và hiển thị của 1 ô
export const cellValue = (key, item) => (key === 'budget' ? item.o.dailyBudget : item.m[key])

// Chữ hiển thị của ô (trừ ngân sách và ROAS: có giao diện riêng)
export function cellText(col, v, cur) {
  if (v == null || Number.isNaN(v)) return '–'
  if (col.money) return money(v, cur)
  if (col.key === 'ctr') return `${fmtDec(v, 2)}%`
  return fmt(v)
}
