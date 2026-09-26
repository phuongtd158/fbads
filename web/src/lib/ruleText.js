// Cách hiển thị điều kiện của rule (danh sách rule, xem trước, nhật ký, trình soạn) — dùng chung cho mọi nơi.
import { fmt, fmtDec } from './format'
import { conditionsOf } from './validate'

export const opText = (op) => (op === '>' ? 'lớn hơn' : 'nhỏ hơn')

// Số liệu của điều kiện: ROAS 2 số lẻ, CTR kèm %, còn lại là số nguyên; ∞ = đã chi tiêu nhưng chưa có kết quả/lượt nhấp
export function fmtMetric(metric, v) {
  if (v == null || Number.isNaN(v)) return '–'
  if (v === Infinity) return '∞'
  if (metric === 'roas') return fmtDec(v)
  if (metric === 'ctr') return `${fmtDec(v, 2)}%`
  return fmt(v)
}

// Vế phải của điều kiện: "150.000" hoặc "120% mục tiêu"
export const rhs = (c) => (c.vs === 'target' ? `${c.factor || 100}% mục tiêu` : fmtMetric(c.metric, Number(c.value)))

export const conditionsOfRule = conditionsOf
export const matchWord = (m) => (m === 'any' ? 'HOẶC' : 'VÀ')

// Điều kiện đã đánh giá (từ xem trước / nhật ký): "CPA 250.000 > 150.000". Ngưỡng null = tài khoản chưa đặt mục tiêu.
export function evaluated(c) {
  const actual = c.inf || c.actualInf ? Infinity : c.actual
  const th = c.threshold == null ? 'chưa có mục tiêu' : fmtMetric(c.metric, c.threshold)
  return { actual: fmtMetric(c.metric, actual), op: c.op === '>' ? '>' : '<', threshold: th, target: c.vs === 'target' ? `${c.factor || 100}% mục tiêu` : '', hit: !!c.hit, unknown: !!c.unknown }
}

// Câu mô tả đầy đủ một rule (trình soạn hiện ngay khi sửa). Trả về các câu ngắn, câu đầu là "Nếu … thì …".
// names: { accounts: [{ id, name }] } để đọc tên tài khoản.
const RANGE_TXT = { today: 'hôm nay', yesterday: 'hôm qua', last_3d: '3 ngày gần nhất', last_7d: '7 ngày gần nhất' }
const METRIC_TXT = { cpa: 'CPA', roas: 'ROAS', spend: 'chi tiêu', results: 'số kết quả', ctr: 'CTR', cpc: 'CPC', cpm: 'CPM', messages: 'số tin nhắn', costPerMessage: 'chi phí mỗi tin nhắn', leads: 'số lead', costPerLead: 'chi phí mỗi lead' }
export function describeRule(r, { accounts = [] } = {}) {
  const unit = r.level === 'adset' ? 'nhóm QC' : 'camp'
  const cs = conditionsOf(r)
  const cond = cs.map((c) => `${METRIC_TXT[c.metric] || c.metric} ${opText(c.op)} ${rhs(c)}`).join(` ${matchWord(r.match).toLowerCase()} `)
  const act = r.action === 'pause' ? `tắt ${unit} đó`
    : r.action === 'notify' ? 'gửi cảnh báo (không đổi gì)'
      : `${r.action === 'increase' ? 'tăng' : 'giảm'} ${r.pct || 0}% ngân sách${r.action === 'increase' && Number(r.maxBudget) > 0 ? `, tối đa ${fmt(r.maxBudget)}` : ''}${r.action === 'decrease' && Number(r.minBudget) > 0 ? `, không dưới ${fmt(r.minBudget)}` : ''}`
  const scope = r.allActive === false
    ? `${(r.targets || []).length} ${unit} đã chọn`
    : `mọi ${unit} đang chạy${(r.accountIds || []).length ? ` trong ${r.accountIds.map((id) => (accounts.find((a) => a.id === id) || { name: id }).name).join(', ')}` : ''}`
  const out = [`Với ${scope}: nếu ${cond || '…'} (tính ${RANGE_TXT[r.range || 'today']}${Number(r.minSpend) > 0 ? `, khi đã chi từ ${fmt(r.minSpend)}` : ''}) thì ${act}.`]
  const extra = []
  if (r.action === 'pause' && r.resume === 'nextday') extra.push(`Bật lại lúc ${r.resumeAt || '06:00'} ngày hôm sau`)
  if (Number(r.cooldownHours) > 0) extra.push(`không lặp lại cho cùng ${unit} trong ${r.cooldownHours} giờ`)
  if (r.from && r.to) extra.push(`chỉ chạy ${r.from}–${r.to}`)
  if (extra.length) out.push(extra.join(', ').replace(/^./, (x) => x.toUpperCase()) + '.')
  return out
}
