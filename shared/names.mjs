// Tên tự đặt + câu tóm tắt cho lịch và rule (trình soạn: để trống tên thì dùng tên này). Dùng chung với kiểm thử.
import { groupThousands } from './money.mjs'
import { scheduleTimes, conditionsOf, LIMITS } from './validate.mjs'

const DAY = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7']
const ORDER = [1, 2, 3, 4, 5, 6, 0]
const money = (v) => { const n = Math.round(Math.abs(Number(v) || 0)); return (Number(v) < 0 ? '-' : '') + groupThousands(String(n)) }
const clip = (s) => (s.length > LIMITS.nameMax ? s.slice(0, LIMITS.nameMax - 1).trimEnd() + '…' : s)
const sorted = (ts) => [...new Set(ts)].sort()

// Ngày chạy: 'Hằng ngày' | 'T2–T6' | 'Cuối tuần' | 'T2, T4, T6'
export function daysText(days = []) {
  const s = new Set((days || []).map(Number))
  if (s.size === 7) return 'Hằng ngày'
  if (s.size === 5 && [1, 2, 3, 4, 5].every((d) => s.has(d))) return 'T2–T6'
  if (s.size === 2 && s.has(6) && s.has(0)) return 'Cuối tuần'
  return ORDER.filter((d) => s.has(d)).map((d) => DAY[d]).join(', ')
}
// Nút chọn nhanh đang khớp với ngày đã chọn: 'all' | 'weekdays' | 'weekend' | ''
export function daysPreset(days = []) {
  const t = daysText(days)
  return t === 'Hằng ngày' ? 'all' : t === 'T2–T6' ? 'weekdays' : t === 'Cuối tuần' ? 'weekend' : ''
}
// Nhiều giờ: tối đa 3 giờ, còn lại "+n giờ"
export function timesText(times = []) {
  const ts = sorted(times)
  if (ts.length <= 3) return ts.join(', ')
  return `${ts.slice(0, 3).join(', ')} +${ts.length - 3} giờ`
}
// Đổi ngân sách: '+20%', '-30%', '= 500.000', '+50.000'
export function budgetText(s) {
  const v = Number(s.value)
  if (!Number.isFinite(v)) return ''
  if (s.mode === 'set') return `= ${money(v)}`
  const sign = v > 0 ? '+' : ''
  return s.mode === 'add' ? `${sign}${money(v)}` : `${sign}${v}%`
}

export function scheduleName(s = {}) {
  const at = timesText(scheduleTimes(s))
  const w = s.window || {}
  const head = s.action === 'window' ? `Bật ${w.on || '--:--'} → tắt ${w.off || '--:--'}`
    : s.action === 'off' ? `Tắt camp ${at}`
      : s.action === 'budget' ? `Ngân sách ${budgetText(s) || '…'} lúc ${at}`
        : `Bật camp ${at}`
  const d = daysText(s.days)
  return clip(d ? `${head} · ${d}` : head)
}

// Câu tóm tắt lịch: "Bật 3 chiến dịch lúc 06:00 và 12:00, hằng ngày."
// count: số mục sẽ áp dụng (null = chưa biết), unit: 'chiến dịch' | 'nhóm QC', auto: theo điều kiện
export function describeSchedule(s = {}, { count = null, unit = 'chiến dịch', auto = false } = {}) {
  const who = count == null ? `các ${unit}` : auto ? `${count} ${unit} đang khớp điều kiện` : count ? `${count} ${unit}` : `các ${unit} bạn chọn`
  const ts = sorted(scheduleTimes(s))
  const at = ts.length ? (ts.length === 2 ? ts.join(' và ') : timesText(ts)) : '…'
  const w = s.window || {}
  const act = s.action === 'window' ? `Bật ${who} lúc ${w.on || '…'} và tắt lúc ${w.off || '…'}`
    : s.action === 'off' ? `Tắt ${who} lúc ${at}`
      : s.action === 'budget' ? `Đổi ngân sách ${who} ${budgetText(s) || '…'} lúc ${at}`
        : `Bật ${who} lúc ${at}`
  const d = daysText(s.days)
  const when = !d ? 'chưa chọn ngày' : d === 'Hằng ngày' ? 'hằng ngày' : d === 'T2–T6' ? 'từ thứ 2 đến thứ 6' : d === 'Cuối tuần' ? 'vào cuối tuần' : `vào ${d}`
  return `${act}, ${when}.`
}

const M = { cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Kết quả', ctr: 'CTR', cpc: 'CPC', cpm: 'CPM', messages: 'Tin nhắn', costPerMessage: 'Chi phí/tin nhắn', leads: 'Lead', costPerLead: 'Chi phí/lead', frequency: 'Tần suất' }
const R = { today: 'hôm nay', yesterday: 'hôm qua', last_3d: '3 ngày', last_7d: '7 ngày' }
const COST = ['cpa', 'spend', 'cpc', 'cpm', 'costPerMessage', 'costPerLead']
function condText(c) {
  const rhs = c.vs === 'target' ? `${c.factor || 100}% mục tiêu`
    : c.vs === 'range' ? `${c.factor || 100}% ${R[c.compareRange] || ''}`.trim()
      : COST.includes(c.metric) ? money(c.value) : String(c.value ?? '')
  // Ngưỡng chi tiêu nâng theo số kết quả: "Chi tiêu > 150.000, 2 Lead: 200.000"
  const tiers = (c.tiers || []).map((t) => `, ${t.count} ${M[c.tierMetric] || c.tierMetric}: ${money(t.value)}`).join('')
  return `${M[c.metric] || c.metric} ${c.op === '<' ? '<' : '>'} ${rhs}${c.vs ? '' : tiers}`
}
// "Tắt camp CPA > 150.000", "Tăng NS nhóm QC ROAS > 2", "Báo khi CPA > 120% mục tiêu"
export function ruleName(r = {}) {
  const unit = r.level === 'adset' ? 'nhóm QC' : 'camp'
  const head = r.action === 'increase' ? `Tăng NS ${unit}` : r.action === 'decrease' ? `Giảm NS ${unit}` : r.action === 'notify' ? 'Báo khi' : `Tắt ${unit}`
  const cs = conditionsOf(r).map(condText).join(r.match === 'any' ? ' hoặc ' : ' & ')
  return clip(cs ? `${head} ${cs}` : head)
}
