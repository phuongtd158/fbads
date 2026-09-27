// Đọc số tiền người dùng gõ: "150000", "150.000", "150k", "1,5tr", "1.5 triệu", "2 tỷ", "200.000đ".
// Trả về số (đã làm tròn), '' nếu để trống, NaN nếu không đọc được. Dùng chung cho giao diện và kiểm thử.
const UNITS = [[/^(k|n|nghìn|nghin|ngàn|ngan)$/, 1e3], [/^(tr|triệu|trieu|m)$/, 1e6], [/^(tỷ|tỉ|ty|ti|b)$/, 1e9]]

export function parseMoney(input) {
  if (typeof input === 'number') return Number.isFinite(input) ? Math.round(input) : NaN
  const s = String(input ?? '').trim().toLowerCase().replace(/\s+/g, '').replace(/(đ|₫|vnđ|vnd|dong|đồng)$/, '')
  if (!s) return ''
  const m = s.match(/^(-?[\d.,]+)([^\d.,-]*)$/)
  if (!m) return NaN
  const [, numPart, unitPart] = m
  let mult = 1
  if (unitPart) {
    const u = UNITS.find(([re]) => re.test(unitPart))
    if (!u) return NaN
    mult = u[1]
  }
  let n
  // Có đơn vị (1,5tr) hoặc chỉ một dấu mà không đúng nhóm 3 chữ số (1.5) → dấu là phần thập phân; còn lại là dấu phân cách hàng nghìn
  if (/^-?\d{1,3}([.,]\d{3})+$/.test(numPart) && !unitPart) n = Number(numPart.replace(/[.,]/g, ''))
  else if (/^-?\d+([.,]\d+)?$/.test(numPart)) n = Number(numPart.replace(',', '.'))
  else return NaN
  return Math.round(n * mult)
}
