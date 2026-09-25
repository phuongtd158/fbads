// Trạng thái bộ lọc của Tổng quan (khoảng ngày, tài khoản, trạng thái, cấp, cột, sắp xếp) — trình duyệt nhớ giữa các lần mở —
// và số liệu theo khoảng ngày. Danh sách camp (state.objs) luôn là số liệu HÔM NAY vì lịch, rule, bộ chọn camp… dùng nó;
// số liệu của khoảng khác chỉ nằm ở đây và chỉ Tổng quan ghép vào bảng.
import { reactive, computed, ref, watch } from 'vue'
import { api } from '../lib/api'
import { state } from './app'
import { normalizeSpec, isToday, specKey, todayIn, rangeLabel, resolveRange } from '../lib/dates'
import { EMPTY, derive } from '../lib/metrics'
import { cleanColumns, COLUMN_KEYS } from '../lib/overviewColumns'

const P = 'fbads.ov.'
const rd = (k, d) => { try { const v = localStorage.getItem(P + k); return v == null ? d : JSON.parse(v) } catch { return d } }
const wr = (k, v) => { try { localStorage.setItem(P + k, JSON.stringify(v)) } catch { /* chế độ riêng tư */ } }
const legacy = (k) => { try { return localStorage.getItem(k) } catch { return null } }

export const tz = () => (state.settings && state.settings.timezone) || 'Asia/Ho_Chi_Minh'
export const todayISO = () => todayIn(tz())

const SORT_KEYS = new Set(['name', 'account', 'delivery', ...COLUMN_KEYS])
const okSort = (v) => (v && SORT_KEYS.has(v.key) && ['asc', 'desc'].includes(v.dir) ? { key: v.key, dir: v.dir } : { key: '', dir: 'desc' }) // key '' = thứ tự như trên Facebook

// đọc cả lựa chọn cũ của bản trước (1 tài khoản, kiểu sắp xếp) để không mất thiết lập của người dùng
const legacyAcc = legacy('fbads.overviewAccount')
let legacySort = null
try { legacySort = JSON.parse(legacy('fbads.overviewSort')) } catch { /* không có */ }

export const ov = reactive({
  spec: normalizeSpec(rd('range'), todayISO()), // { preset } | { since, until }
  accounts: (() => { const v = rd('accounts', null); return Array.isArray(v) ? v.map(String) : legacyAcc ? [legacyAcc] : [] })(), // [] = mọi tài khoản
  status: ['all', 'on', 'off'].includes(rd('status')) ? rd('status') : 'all',
  level: rd('level') === 'adset' ? 'adset' : 'campaign',
  columns: cleanColumns(rd('columns')),
  sort: okSort(rd('sort', legacySort)),
  data: null, // { key, since, until, days, at, stale, metrics } của khoảng đang xem (khác hôm nay)
  loading: false,
  err: '',
})
for (const k of ['spec', 'accounts', 'status', 'level', 'columns', 'sort']) watch(() => ov[k], (v) => wr(k === 'spec' ? 'range' : k, v), { deep: true })

// ----- Khoảng ngày -----
const clock = ref(0) // đổi để tính lại nhãn khi qua ngày mới
export const rangeInfo = computed(() => {
  clock.value // eslint-disable-line no-unused-expressions
  const t = todayISO(), l = rangeLabel(ov.spec, t)
  return { ...l, today: isToday(ov.spec), days: ov.data && ov.data.key === specKey(ov.spec) ? ov.data.days : resolveRange(ov.spec, t).days }
})

let seq = 0
// Tải số liệu của khoảng đang chọn. Đổi khoảng liên tục thì chỉ nhận câu trả lời của lần chọn mới nhất.
export async function loadRange({ force = false, bg = false } = {}) {
  clock.value++
  if (isToday(ov.spec)) { ov.data = null; ov.err = ''; ov.loading = false; return }
  const me = ++seq
  ov.loading = true
  try {
    const qs = ov.spec.preset ? `range=${ov.spec.preset}` : `since=${ov.spec.since}&until=${ov.spec.until}`
    const r = await api(`insights?${qs}${force ? '&refresh=1' : ''}`, 'GET', undefined, { bg })
    if (me !== seq) return
    ov.data = r; ov.err = ''
  } catch (e) {
    if (me === seq && !e.silent) ov.err = e.message
  } finally {
    if (me === seq) ov.loading = false
  }
}
export function setSpec(spec) {
  ov.spec = spec; ov.data = null; ov.err = ''
  return loadRange()
}

// Có số liệu của khoảng đang chọn chưa (hôm nay luôn có sẵn trong danh sách camp)
export const rangeReady = computed(() => isToday(ov.spec) || (!!ov.data && ov.data.key === specKey(ov.spec)))

// Số liệu của 1 camp/nhóm QC trong khoảng đang chọn; chưa có dữ liệu (không phát sinh chi tiêu) → toàn 0
export function metricsOf(o) {
  if (isToday(ov.spec)) return o.metrics || EMPTY
  const d = ov.data
  return (d && d.key === specKey(ov.spec) && d.metrics[o.id]) || EMPTY
}
// currency ở ngoài cùng để gom nhóm theo loại tiền (groupByCurrency)
export const itemOf = (o) => ({ o, m: derive(metricsOf(o)), currency: o.currency })

export function clearFilters() { ov.accounts = []; ov.status = 'all' }
