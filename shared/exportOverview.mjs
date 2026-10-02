// Xuất bảng Tổng quan ra Excel: đúng như đang xem (cấp, bộ lọc, thứ tự, các cột đang bật, khoảng ngày)
// nhưng đủ mọi dòng (bảng chỉ vẽ từng trang). Số trong file là số thật để Excel cộng/lọc/sắp xếp được.
import { decimalsOf } from './accounts.mjs'

// Độ rộng cột (đơn vị ký tự của Excel)
const W = { name: 46, id: 20, account: 26, delivery: 22, currency: 9 }

// key → định dạng số trong Excel. Cột tiền: VND (không số lẻ) → #,##0, USD → #,##0.00
const fmtOf = (col, cur) => (col.money || col.key === 'budget' ? (decimalsOf(cur) ? 'dec2' : 'int') : col.key === 'roas' ? 'ratio' : col.key === 'ctr' ? 'pct' : 'int')
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

// Tên file: chien-dich_2026-09-01_2026-09-30.xlsx (hôm nay: chien-dich_2026-10-02.xlsx)
export function exportFileName(level, since, until) {
  const base = level === 'adset' ? 'nhom-qc' : 'chien-dich'
  const d = since && until && since !== until ? `${since}_${until}` : until || since || ''
  return `${base}${d ? '_' + d : ''}.xlsx`
}

// opts: {
//   items: [{ o, m }] đã lọc + sắp xếp, cols: [{ key, label, money }], level: 'campaign' | 'adset', showAcc,
//   deliveryLabel(o), currencyOf(o), campaignName(o) (tên chiến dịch chứa nhóm QC), total: kết quả totals() của các dòng (hoặc null), mixed: nhiều loại tiền,
//   info: [[nhãn, giá trị]] cho sheet "Thông tin"
// }
export function overviewSheets({ items, cols, level, showAcc, deliveryLabel, currencyOf, campaignName = () => '', total, mixed, info = [] }) {
  const lead = [
    { h: level === 'adset' ? 'Nhóm quảng cáo' : 'Chiến dịch', w: W.name, v: (it) => it.o.name },
    ...(level === 'adset' ? [{ h: 'Chiến dịch', w: W.name, v: (it) => campaignName(it.o) }] : []),
    { h: 'ID', w: W.id, v: (it) => String(it.o.id) },
    ...(showAcc ? [{ h: 'Tài khoản', w: W.account, v: (it) => it.o.accountName || it.o.accountId || '' }] : []),
    { h: 'Phân phối', w: W.delivery, v: (it) => deliveryLabel(it.o) },
    ...(mixed ? [{ h: 'Loại tiền', w: W.currency, v: (it) => currencyOf(it.o) }] : []),
  ]
  const header = [...lead.map((c) => c.h), ...cols.map((c) => (c.key === 'ctr' ? 'CTR (%)' : c.label))]
  const rows = items.map((it) => {
    const cur = currencyOf(it.o)
    return [
      ...lead.map((c) => c.v(it)),
      ...cols.map((c) => {
        const v = num(c.key === 'budget' ? it.o.dailyBudget : it.m[c.key])
        // ROAS khi không có doanh thu: để trống như trên bảng ("–")
        if (v == null || (c.key === 'roas' && !it.m.revenue)) return null
        return { v, fmt: fmtOf(c, cur) }
      }),
    ]
  })
  const out = [header, ...rows]
  if (total && items.length > 1) {
    const cur = currencyOf(items[0].o)
    out.push([
      { v: `Tổng (${items.length} mục)`, bold: true }, ...lead.slice(1).map(() => null),
      ...cols.map((c) => {
        // nhiều loại tiền thì không cộng chung cột tiền được
        if (mixed && (c.money || c.key === 'budget' || c.key === 'roas')) return null
        const v = num(c.key === 'budget' ? (total.budgetRows ? total.budget : null) : total[c.key])
        if (v == null || (c.key === 'roas' && !total.revenue)) return null
        return { v, fmt: fmtOf(c, cur), bold: true }
      }),
    ])
  }
  const main = {
    name: level === 'adset' ? 'Nhóm QC' : 'Chiến dịch',
    header: true,
    filter: true,
    cols: [...lead.map((c) => ({ width: c.w })), ...cols.map((c) => ({ width: Math.max(12, Math.min(24, c.label.length + 4)) }))],
    // nút lọc của Excel chỉ phủ các dòng dữ liệu, không phủ dòng Tổng
    rows: out,
    filterRows: rows.length + 1,
  }
  const sheets = [main]
  if (info.length) sheets.push({ name: 'Thông tin', cols: [{ width: 22 }, { width: 60 }], rows: info.map(([k, v]) => [{ v: k, bold: true }, v]) })
  return sheets
}
