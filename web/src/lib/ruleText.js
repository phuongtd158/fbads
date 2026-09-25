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
