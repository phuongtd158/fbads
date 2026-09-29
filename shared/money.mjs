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

// Ô nhập tiền trên giao diện: chỉ nhận chữ số, gõ đến đâu tự thêm dấu chấm hàng nghìn đến đó ("1000000" → "1.000.000").
// caret = vị trí con trỏ trong chuỗi vừa gõ; trả về { text, caret } để con trỏ vẫn đứng sau đúng chữ số đó.
// negative: cho gõ dấu - ở đầu (ô cộng/trừ số tiền).
const MAX_DIGITS = 15 // quá 15 chữ số thì Number mất chính xác
export const groupThousands = (digits) => digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.')

export function formatMoneyTyping(input, caret, { negative = false } = {}) {
  const s = String(input ?? '')
  const at = caret == null ? s.length : caret
  const neg = negative && /^\s*-/.test(s)
  const raw = s.replace(/\D/g, '')
  let digits = raw.replace(/^0+(?=\d)/, '').slice(0, MAX_DIGITS)
  // số chữ số đứng trước con trỏ, trừ đi các số 0 thừa ở đầu vừa bị bỏ
  let before = Math.min(Math.max(0, s.slice(0, at).replace(/\D/g, '').length - (raw.length - raw.replace(/^0+(?=\d)/, '').length)), digits.length)
  const text = (neg ? '-' : '') + groupThousands(digits)
  let pos = neg ? 1 : 0
  for (let seen = 0; pos < text.length && seen < before; pos++) if (/\d/.test(text[pos])) seen++
  return { text, caret: pos }
}
