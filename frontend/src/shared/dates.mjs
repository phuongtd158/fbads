// Khoảng ngày cho số liệu ở Tổng quan, giống bộ chọn ngày của Ads Manager. Dùng chung cho server (kiểm tra tham số,
// gọi Facebook) và giao diện (nhãn, mốc ngày). Chỉ làm việc với chuỗi 'YYYY-MM-DD' nên không bị lệch múi giờ.
//
// spec là 1 trong 2 dạng:  { preset: 'last_7d' }  hoặc  { since: '2026-09-01', until: '2026-09-20' }
// Các mốc "n ngày qua" của Facebook KHÔNG tính hôm nay (7 ngày qua = 7 ngày liền trước hôm nay), giống Ads Manager.

// fb: tên date_preset của Facebook Insights; không có fb thì gửi dạng time_range theo ngày đã tính
export const PRESETS = [
  { id: 'today', label: 'Hôm nay', fb: 'today' },
  { id: 'yesterday', label: 'Hôm qua', fb: 'yesterday' },
  { id: 'today_yesterday', label: 'Hôm nay và hôm qua' },
  { id: 'last_3d', label: '3 ngày qua', fb: 'last_3d' },
  { id: 'last_7d', label: '7 ngày qua', fb: 'last_7d' },
  { id: 'last_14d', label: '14 ngày qua', fb: 'last_14d' },
  { id: 'last_28d', label: '28 ngày qua', fb: 'last_28d' },
  { id: 'last_30d', label: '30 ngày qua', fb: 'last_30d' },
  { id: 'this_week_mon_today', label: 'Tuần này', fb: 'this_week_mon_today' },
  { id: 'last_week_mon_sun', label: 'Tuần trước', fb: 'last_week_mon_sun' },
  { id: 'this_month', label: 'Tháng này', fb: 'this_month' },
  { id: 'last_month', label: 'Tháng trước', fb: 'last_month' },
  { id: 'maximum', label: 'Tối đa (từ trước tới nay)', fb: 'maximum' },
]
export const PRESET_IDS = new Set(PRESETS.map((p) => p.id))
export const presetOf = (id) => PRESETS.find((p) => p.id === id) || null
export const DEFAULT_SPEC = { preset: 'today' }
export const MAX_BACK_MONTHS = 37 // Facebook chỉ giữ số liệu Insights khoảng 37 tháng

// ----- Ngày dạng 'YYYY-MM-DD' -----
const pad = (n) => String(n).padStart(2, '0')
const ISO = /^(\d{4})-(\d{2})-(\d{2})$/
const utc = (iso) => { const m = ISO.exec(iso); return Date.UTC(+m[1], +m[2] - 1, +m[3]) }
const fromUtc = (t) => { const d = new Date(t); return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` }

export function isISODate(s) {
  const m = typeof s === 'string' ? ISO.exec(s) : null
  if (!m) return false
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]))
  return d.getUTCFullYear() === +m[1] && d.getUTCMonth() === +m[2] - 1 && d.getUTCDate() === +m[3] // loại 2026-02-30
}
export const addDays = (iso, n) => fromUtc(utc(iso) + n * 864e5)
export const daysBetween = (a, b) => Math.round((utc(b) - utc(a)) / 864e5) + 1 // gồm cả 2 đầu
const dow = (iso) => new Date(utc(iso)).getUTCDay() // 0 = Chủ nhật
const firstOfMonth = (iso) => `${iso.slice(0, 7)}-01`
const lastOfPrevMonth = (iso) => addDays(firstOfMonth(iso), -1)

export function addMonths(iso, n) {
  const m = ISO.exec(iso), total = +m[1] * 12 + (+m[2] - 1) + n
  const y = Math.floor(total / 12), mo = total % 12
  const last = new Date(Date.UTC(y, mo + 1, 0)).getUTCDate()
  return `${y}-${pad(mo + 1)}-${pad(Math.min(+m[3], last))}`
}
// Ngày sớm nhất được phép chọn
export const minDate = (today) => firstOfMonth(addMonths(today, -MAX_BACK_MONTHS))

// Hôm nay theo múi giờ tz (vd 'Asia/Ho_Chi_Minh'); múi giờ sai → dùng giờ máy
export function todayIn(tz, now = Date.now()) {
  try { return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now) } catch {
    const d = new Date(now); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
}

// ----- Đổi spec thành ngày cụ thể -----
// → { since, until, days }; 'maximum' không có ngày bắt đầu (since = null, days = null)
export function resolveRange(spec, today) {
  if (spec.preset) {
    let since, until
    switch (spec.preset) {
      case 'today': since = until = today; break
      case 'yesterday': since = until = addDays(today, -1); break
      case 'today_yesterday': since = addDays(today, -1); until = today; break
      case 'this_week_mon_today': since = addDays(today, -((dow(today) + 6) % 7)); until = today; break
      case 'last_week_mon_sun': { const mon = addDays(today, -((dow(today) + 6) % 7)); since = addDays(mon, -7); until = addDays(mon, -1); break }
      case 'this_month': since = firstOfMonth(today); until = today; break
      case 'last_month': until = lastOfPrevMonth(today); since = firstOfMonth(until); break
      case 'maximum': return { since: null, until: today, days: null }
      default: {
        const n = /^last_(\d+)d$/.exec(spec.preset)
        if (!n) throw new Error(`Khoảng ngày không hợp lệ: ${spec.preset}`)
        since = addDays(today, -Number(n[1])); until = addDays(today, -1)
      }
    }
    return { since, until, days: daysBetween(since, until) }
  }
  return { since: spec.since, until: spec.until, days: daysBetween(spec.since, spec.until) }
}

export const specKey = (spec) => (spec.preset ? `p:${spec.preset}` : `r:${spec.since}_${spec.until}`)
export const isToday = (spec) => !!spec && spec.preset === 'today'
// hôm nay có nằm trong khoảng không (số liệu còn đang thay đổi)
export const includesToday = (spec, today) => { const r = resolveRange(spec, today); return r.until >= today }

// Tham số gửi Facebook Insights: date_preset khi có tên sẵn, không thì time_range theo ngày đã tính
export function fbParams(spec, today) {
  const p = spec.preset && presetOf(spec.preset)
  if (p && p.fb) return { date_preset: p.fb }
  const r = resolveRange(spec, today)
  return { time_range: JSON.stringify({ since: r.since, until: r.until }) }
}

// ----- Kiểm tra dữ liệu người dùng gửi (query string hoặc JSON) -----
// input: { range: 'last_7d' } | { preset: 'last_7d' } | { since, until }; trống → hôm nay
export function parseRange(input = {}, today) {
  const preset = input.range ?? input.preset
  const hasCustom = input.since != null || input.until != null
  if (hasCustom) {
    const { since, until } = input
    if (!isISODate(since) || !isISODate(until)) return { ok: false, error: 'Ngày bắt đầu và ngày kết thúc phải có dạng năm-tháng-ngày (vd 2026-09-01).' }
    if (since > until) return { ok: false, error: 'Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.' }
    if (until > today) return { ok: false, error: 'Không chọn được ngày trong tương lai.' }
    if (since < minDate(today)) return { ok: false, error: `Facebook chỉ giữ số liệu khoảng ${MAX_BACK_MONTHS} tháng gần nhất.` }
    const spec = { since, until }
    return { ok: true, spec, key: specKey(spec) }
  }
  if (preset == null || preset === '') return { ok: true, spec: { ...DEFAULT_SPEC }, key: specKey(DEFAULT_SPEC) }
  if (!PRESET_IDS.has(preset)) return { ok: false, error: `Khoảng ngày “${preset}” không được hỗ trợ.` }
  const spec = { preset }
  return { ok: true, spec, key: specKey(spec) }
}

// ----- Hiển thị -----
export const fmtDMY = (iso) => `${iso.slice(8)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`
const fmtDM = (iso) => `${iso.slice(8)}/${iso.slice(5, 7)}`
function fmtRange(since, until) {
  if (since === until) return fmtDMY(since)
  return since.slice(0, 4) === until.slice(0, 4) ? `${fmtDM(since)} – ${fmtDMY(until)}` : `${fmtDMY(since)} – ${fmtDMY(until)}`
}

// { title, dates } — title: tên khoảng ("7 ngày qua" hoặc "Tuỳ chọn"), dates: ngày cụ thể ("18/09 – 24/09/2026")
export function rangeLabel(spec, today) {
  const r = resolveRange(spec, today)
  const p = spec.preset && presetOf(spec.preset)
  const title = p ? p.label : 'Tuỳ chọn'
  if (r.since == null) return { title: 'Tối đa', dates: `đến ${fmtDMY(r.until)}` }
  return { title, dates: fmtRange(r.since, r.until) }
}

// Đọc lại spec đã lưu (localStorage) một cách an toàn
export function normalizeSpec(v, today) {
  if (!v || typeof v !== 'object') return { ...DEFAULT_SPEC }
  const r = parseRange(v.preset ? { preset: v.preset } : { since: v.since, until: v.until }, today)
  return r.ok ? r.spec : { ...DEFAULT_SPEC }
}
