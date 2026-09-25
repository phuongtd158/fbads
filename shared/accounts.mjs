// Nhiều tài khoản quảng cáo: nhãn hiển thị + gom theo loại tiền. Dùng chung cho giao diện và kiểm thử.
// Mỗi camp/nhóm QC do server gắn sẵn { accountId, accountName, currency } của tài khoản chứa nó.

// Loại tiền Facebook tính ngân sách theo đơn vị nhỏ nhất (VND: đồng, USD: cent) — giống NO_DECIMAL ở lib/fb.js
const NO_DECIMAL = new Set(['VND', 'JPY', 'KRW', 'CLP', 'ISK', 'PYG'])
export const decimalsOf = (cur) => (NO_DECIMAL.has(cur) ? 0 : 2)

export const currencyOf = (o, fallback = '') => (o && o.currency) || fallback
export const accountLabel = (o) => (o && (o.accountName || o.accountId)) || ''

// [{ currency, items }] theo thứ tự xuất hiện. VND và USD không cộng chung được nên phải tách nhóm trước khi tính tổng.
export function groupByCurrency(list, fallback = '') {
  const m = new Map()
  for (const o of list) {
    const c = currencyOf(o, fallback)
    if (!m.has(c)) m.set(c, [])
    m.get(c).push(o)
  }
  return [...m].map(([currency, items]) => ({ currency, items }))
}

// { [accountId]: số mục } — hiện cạnh tên tài khoản trong ô lọc
export function countByAccount(list) {
  const out = {}
  for (const o of list) if (o.accountId) out[o.accountId] = (out[o.accountId] || 0) + 1
  return out
}

// Tên hiển thị của một mục theo id (lịch/rule chỉ lưu id): "Tên camp" hoặc "Tên camp · Tên tài khoản" khi có nhiều tài khoản
export function labelOf(objs, id, multi) {
  const o = (objs || []).find((x) => x.id === id)
  if (!o) return { name: id, account: '', full: id }
  const account = multi ? accountLabel(o) : ''
  return { name: o.name, account, full: account ? `${o.name} · ${account}` : o.name }
}
