// Chọn camp / nhóm QC theo ĐIỀU KIỆN (thay vì chọn từng mục) và tính ngân sách mới — dùng chung cho:
//  - "Đổi ngân sách hàng loạt" trên giao diện (xem trước + chạy ngay)
//  - lịch "Theo điều kiện": engine lọc lại theo số liệu lúc chạy, nên camp/nhóm mới tạo cũng được áp dụng nếu khớp.
// Chỉ tính toán, không gọi API.
import { LIMITS } from './validate.mjs'
import { DELIVERY, deliveryMap } from './delivery.mjs'

// Đọc số tiền kiểu Việt: "500000", "500.000", "500,000", "500k", "1,5tr", "1.5 triệu"
export function parseMoney(input) {
  if (typeof input === 'number') return input
  let s = String(input ?? '').trim().toLowerCase().replace(/\s+/g, '').replace(/đ|vnd$/g, '')
  if (!s) return NaN
  let mul = 1
  const unit = s.match(/(k|nghìn|ngàn|tr|triệu|m)$/)
  if (unit) { mul = /^(k|nghìn|ngàn)$/.test(unit[1]) ? 1e3 : 1e6; s = s.slice(0, -unit[1].length) }
  const neg = s.startsWith('-')
  if (neg || s.startsWith('+')) s = s.slice(1)
  if (!/^[\d.,]+$/.test(s)) return NaN
  // có đơn vị (k/tr): dấu . hoặc , là phần thập phân ("1,5tr"); không có: dấu . , ngăn cách hàng nghìn ("500.000")
  const n = mul > 1 ? Number(s.replace(',', '.')) : /^\d{1,3}([.,]\d{3})+$/.test(s) ? Number(s.replace(/[.,]/g, '')) : Number(s.replace(',', '.'))
  return Number.isFinite(n) ? (neg ? -1 : 1) * n * mul : NaN
}

// Điều kiện theo ngân sách/ngày hiện tại (khoá phải khớp FILTER_OPS trong validate.mjs)
export const CONDS = {
  any: { label: 'Bất kỳ', test: () => true },
  lt: { label: 'Dưới', test: (b, x) => b < x },
  lte: { label: 'Từ … trở xuống', test: (b, x) => b <= x },
  gt: { label: 'Trên', test: (b, x) => b > x },
  gte: { label: 'Từ … trở lên', test: (b, x) => b >= x },
  between: { label: 'Trong khoảng', test: (b, x, y) => b >= Math.min(x, y) && b <= Math.max(x, y) },
}
export const MODES = { set: 'Đặt bằng', percent: 'Tăng/giảm theo %', add: 'Cộng/trừ số tiền' }

const money = (n) => Math.round(n).toLocaleString('vi-VN')
const COND_TEXT = { lt: 'dưới', lte: 'từ %x trở xuống', gt: 'trên', gte: 'từ %x trở lên' }

// Mô tả điều kiện cho người đọc, vd "Nhóm QC ngân sách dưới 100.000 · tên chứa “Phương” · đang chạy"
// accountName(id) (không bắt buộc): tên tài khoản quảng cáo để hiện thay cho mã số
export function describeFilter(f = {}, accountName = (id) => id) {
  const parts = [f.level === 'adset' ? 'Nhóm QC' : 'Chiến dịch']
  if (f.op === 'between') parts[0] += ` ngân sách từ ${money(Math.min(f.x, f.y))} đến ${money(Math.max(f.x, f.y))}`
  else if (COND_TEXT[f.op]) parts[0] += ` ngân sách ${COND_TEXT[f.op].includes('%x') ? COND_TEXT[f.op].replace('%x', money(f.x)) : `${COND_TEXT[f.op]} ${money(f.x)}`}`
  if (f.name) parts.push(`tên chứa “${f.name}”`)
  if (f.onlyRunning) parts.push('đang chạy')
  if (f.account) parts.push(`tài khoản ${accountName(f.account)}`)
  if (parts.length === 1 && (!f.op || f.op === 'any')) parts[0] = f.level === 'adset' ? 'Mọi nhóm QC' : 'Mọi chiến dịch'
  return parts.join(' · ')
}

// Các mục khớp điều kiện { level, op, x, y, name, onlyRunning, account }. Bỏ qua mục đã lưu trữ/xoá.
// "Đang chạy" tính như cột Phân phối (xét cả nhóm QC bên trong camp).
export function matchFilter(objs, f = {}, now = Date.now()) {
  const level = f.level === 'adset' ? 'adset' : 'campaign'
  const q = String(f.name || '').trim().toLowerCase()
  const c = CONDS[f.op] || CONDS.any
  const dm = f.onlyRunning ? deliveryMap(objs, now) : null
  return objs.filter((o) => o.level === level
    && !['ARCHIVED', 'DELETED'].includes(o.effective)
    && (!f.account || o.accountId === f.account)
    && (!dm || (DELIVERY[dm[o.id]] || {}).running)
    && (!q || String(o.name).toLowerCase().includes(q))
    && (c === CONDS.any || (o.dailyBudget != null && c.test(o.dailyBudget, f.x, f.y))))
}

export function nextBudget(cur, { mode, value }) {
  const n = mode === 'set' ? value : mode === 'percent' ? cur * (1 + value / 100) : cur + value
  return Math.round(n)
}

// Đọc form "Đổi ngân sách hàng loạt" (chữ người gõ). Tách 2 phần để danh sách lọc hiện ra
// (và chọn được) trước khi nhập ngân sách mới.
// Phần lọc → { errors, filter }
export function readFilter(f) {
  const e = {}
  const x = parseMoney(f.x), y = parseMoney(f.y)
  if (f.op !== 'any' && !(x >= 0)) e.x = 'Nhập mức ngân sách để so sánh (vd 100000 hoặc 100k)'
  if (f.op === 'between' && !(y >= 0)) e.y = 'Nhập mức thứ hai của khoảng'
  return { errors: e, filter: { level: f.level, op: f.op, x, y, name: f.name || '', onlyRunning: !!f.onlyRunning, account: f.account || '' } }
}
// Phần "đổi thành" → { errors, action }
export function readAction(f) {
  const e = {}
  const v = f.mode === 'percent' ? Number(String(f.value ?? '').replace(',', '.') || NaN) : parseMoney(f.value)
  if (!Number.isFinite(v)) e.value = f.mode === 'percent' ? 'Nhập số % (âm để giảm, vd -20)' : 'Nhập số tiền (vd 500000 hoặc 500k)'
  else if (f.mode === 'set' && v <= 0) e.value = 'Ngân sách mới phải lớn hơn 0'
  else if (f.mode === 'percent' && (v === 0 || v < -LIMITS.rulePctDecreaseMax || v > LIMITS.scheduleSetPctMax)) e.value = `% phải khác 0, từ -${LIMITS.rulePctDecreaseMax} đến +${LIMITS.scheduleSetPctMax}`
  else if (f.mode === 'add' && v === 0) e.value = 'Số tiền cộng/trừ phải khác 0'
  return { errors: e, action: { mode: f.mode, value: v } }
}
export function readForm(f) {
  const a = readFilter(f), b = readAction(f)
  return { errors: { ...a.errors, ...b.errors }, filter: a.filter, action: b.action }
}

// Ngân sách mới của 1 mục: kind 'change' (sẽ đổi), 'same' (đã đúng mức, bỏ qua), 'invalid' (về ≤ 0 hoặc quá lớn, bỏ qua)
export function budgetChange(o, action) {
  const to = nextBudget(o.dailyBudget, action)
  if (!(to > 0) || to > LIMITS.budgetMax) return { to, kind: 'invalid' }
  if (to === Math.round(o.dailyBudget)) return { to, kind: 'same' }
  return { to, kind: 'change' }
}

// Lọc + tính ngân sách mới cho mọi mục khớp. Trả { items: [{ o, from, to }], skipped: { noBudget, same, invalid } }
export function planBulk(objs, { filter, action }, now = Date.now()) {
  const items = [], skipped = { noBudget: 0, same: 0, invalid: 0 }
  for (const o of matchFilter(objs, filter, now)) {
    if (o.dailyBudget == null) { skipped.noBudget++; continue } // CBO / ngân sách trọn đời: không đổi được ở cấp này
    const c = budgetChange(o, action)
    if (c.kind !== 'change') { skipped[c.kind]++; continue }
    items.push({ o, from: o.dailyBudget, to: c.to })
  }
  return { items, skipped }
}
