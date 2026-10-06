<script setup>
// Xu hướng 30 ngày của một camp / nhóm quảng cáo: biểu đồ theo ngày (chi tiêu, kết quả, CPA, ROAS),
// đánh dấu những ngày tool hoặc bạn đã bật/tắt/đổi ngân sách (lấy từ Nhật ký) để thấy thay đổi đó có tác dụng không.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { RefreshCw, TrendingUp, TrendingDown, Power, Wallet } from 'lucide-vue-next'
import { api } from '../lib/api'
import { fmt, fmtDec, fmtCompact, timeOf } from '../lib/format'
import { money } from '../lib/overviewColumns'
import Modal from './Modal.vue'
import Segmented from './Segmented.vue'
import Skeleton from './Skeleton.vue'
import Callout from './Callout.vue'
import Btn from './Btn.vue'

const props = defineProps({ modelValue: Boolean, obj: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])
const open = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) })

const data = ref(null), error = ref(''), loading = ref(false)
async function load(refresh = false) {
  if (!props.obj) return
  loading.value = true; error.value = ''
  try { data.value = await api(`objects/${encodeURIComponent(props.obj.id)}/trend${refresh ? '?refresh=1' : ''}`, 'GET', undefined, { bg: true }) }
  catch (e) { error.value = e.message; if (!refresh) data.value = null }
  finally { loading.value = false }
}
watch(() => [props.modelValue, props.obj && props.obj.id], ([o]) => { if (o) { data.value = null; hover.value = null; load() } }, { immediate: true })

const cur = computed(() => (props.obj && props.obj.currency) || '')
const days = computed(() => (data.value ? data.value.days : []))
const hasRevenue = computed(() => days.value.some((d) => d.revenue > 0))
const metric = ref('spend')
const METRICS = computed(() => [
  { value: 'spend', label: 'Chi tiêu' },
  { value: 'results', label: 'Kết quả' },
  { value: 'cpa', label: 'CPA' },
  ...(hasRevenue.value ? [{ value: 'roas', label: 'ROAS' }] : []),
])
watch(hasRevenue, (v) => { if (!v && metric.value === 'roas') metric.value = 'spend' })
const isLine = computed(() => metric.value === 'cpa' || metric.value === 'roas') // tỉ lệ: đường; số cộng dồn được: cột
const valOf = (d) => d[metric.value]
const fmtVal = (v, k = metric.value) => (v == null ? '–' : k === 'spend' || k === 'cpa' ? money(v, cur.value) : k === 'roas' ? fmtDec(v) : fmt(v))

// ----- Tổng kết -----
const sum = (list) => {
  const t = list.reduce((a, d) => ({ spend: a.spend + d.spend, results: a.results + d.results, revenue: a.revenue + (d.revenue || 0) }), { spend: 0, results: 0, revenue: 0 })
  return { ...t, cpa: t.results ? t.spend / t.results : null, roas: t.spend && t.revenue ? t.revenue / t.spend : null }
}
const total = computed(() => sum(days.value))
// 7 ngày gần nhất (không tính hôm nay, vì chưa hết ngày) so với 7 ngày trước đó
const week = computed(() => {
  const full = days.value.slice(0, -1)
  if (full.length < 14) return null
  const a = sum(full.slice(-7)), b = sum(full.slice(-14, -7)), k = metric.value
  if (a[k] == null || !b[k]) return null
  const pct = Math.round((a[k] / b[k] - 1) * 100)
  const better = k === 'cpa' ? pct < 0 : k === 'spend' ? null : pct > 0 // chi tiêu tăng/giảm không tốt hay xấu
  return { pct, better }
})

// ----- Đánh dấu thay đổi -----
const EV = { off: { label: 'Tắt', tone: 'danger', icon: Power }, on: { label: 'Bật', tone: 'success', icon: Power }, budget: { label: 'Đổi ngân sách', tone: 'info', icon: Wallet } }
const eventsByDate = computed(() => {
  const m = {}
  for (const e of (data.value && data.value.events) || []) (m[e.date] = m[e.date] || []).push(e)
  return m
})
const events = computed(() => [...((data.value && data.value.events) || [])].reverse())
const dm = (iso) => `${iso.slice(8)}/${iso.slice(5, 7)}`

// ----- Biểu đồ (SVG, đo bề rộng thật để chữ không bị méo) -----
const box = ref(null), W = ref(640)
const H = 240, PAD = { l: 52, r: 12, t: 22, b: 26 }
let ro
watch(box, (el) => {
  if (ro) ro.disconnect()
  if (!el) return
  ro = new ResizeObserver(([e]) => { W.value = Math.max(280, Math.round(e.contentRect.width)) })
  ro.observe(el)
})
onBeforeUnmount(() => ro && ro.disconnect())

const plotW = computed(() => W.value - PAD.l - PAD.r)
const plotH = H - PAD.t - PAD.b
const step = computed(() => plotW.value / Math.max(1, days.value.length))
const xMid = (i) => PAD.l + step.value * (i + 0.5)
// Trục: từ 0 tới số tròn gần nhất trên giá trị lớn nhất, 4 vạch
const scale = computed(() => {
  const max = Math.max(0, ...days.value.map(valOf).filter((v) => v != null))
  if (!max) return { top: 1, ticks: [0] }
  const raw = max / 4, mag = 10 ** Math.floor(Math.log10(raw)), n = raw / mag
  const nice = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag
  const top = nice * Math.ceil(max / nice)
  const ticks = []
  for (let v = 0; v <= top + nice / 2; v += nice) ticks.push(v)
  return { top, ticks }
})
const y = (v) => PAD.t + plotH - (v / scale.value.top) * plotH
const tickText = (v) => (metric.value === 'roas' ? fmtDec(v, v % 1 ? 1 : 0) : fmtCompact(v))
const barW = computed(() => Math.max(2, Math.min(18, step.value - 2))) // khoảng hở 2px giữa các cột
const bars = computed(() => days.value.map((d, i) => {
  const v = valOf(d) || 0, top = y(v), h = PAD.t + plotH - top
  const w = barW.value, x = xMid(i) - w / 2, r = Math.min(4, w / 2, h)
  // cột bo tròn 4px ở đầu, đáy vuông nằm trên trục
  const path = h <= 0 ? '' : `M${x},${top + h}V${top + r}Q${x},${top} ${x + r},${top}H${x + w - r}Q${x + w},${top} ${x + w},${top + r}V${top + h}Z`
  return { path, today: i === days.value.length - 1 }
}))
// Đường: ngày không có giá trị (vd không có kết quả nên không có CPA) làm đứt đường
const linePath = computed(() => {
  let p = '', pen = false
  days.value.forEach((d, i) => {
    const v = valOf(d)
    if (v == null) { pen = false; return }
    p += `${pen ? 'L' : 'M'}${xMid(i).toFixed(1)},${y(v).toFixed(1)}`
    pen = true
  })
  return p
})
const dots = computed(() => days.value.map((d, i) => (valOf(d) == null ? null : { x: xMid(i), y: y(valOf(d)) })))
const xLabels = computed(() => {
  const n = days.value.length, every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(plotW.value / 56))))
  return days.value.map((d, i) => ({ i, text: dm(d.date) })).filter(({ i }) => (n - 1 - i) % every === 0)
})
const markers = computed(() => days.value.map((d, i) => ({ i, list: eventsByDate.value[d.date] || [] })).filter((m) => m.list.length))

// ----- Rê chuột / chạm để xem từng ngày -----
const hover = ref(null)
const tipDay = computed(() => (hover.value == null ? null : days.value[hover.value]))
const tipLeft = computed(() => {
  if (hover.value == null) return 0
  const x = xMid(hover.value)
  return Math.min(Math.max(x, 110), W.value - 110)
})
function pick(el, clientX) {
  const r = el.getBoundingClientRect()
  const i = Math.floor((clientX - r.left - PAD.l) / step.value)
  hover.value = i >= 0 && i < days.value.length ? i : null
}
function onKey(e) {
  if (!days.value.length) return
  if (e.key === 'ArrowLeft') hover.value = Math.max(0, (hover.value ?? days.value.length) - 1)
  else if (e.key === 'ArrowRight') hover.value = Math.min(days.value.length - 1, (hover.value ?? -1) + 1)
  else return
  e.preventDefault()
}

const subtitle = computed(() => {
  if (!props.obj) return ''
  const lv = props.obj.level === 'adset' ? 'Nhóm quảng cáo' : 'Chiến dịch'
  return data.value ? `${lv} · ${dm(data.value.since)} – ${dm(data.value.until)}` : lv
})
</script>

<template>
  <Modal v-model="open" :title="obj ? obj.name : ''" :subtitle="subtitle" width="820px">
    <div class="tp">
      <Callout v-if="error && !data" tone="danger">{{ error }}</Callout>
      <template v-else>
        <div class="tiles">
          <div class="tile"><span class="lb">Chi tiêu 30 ngày</span><b v-if="data" class="num">{{ money(total.spend, cur) }}</b><Skeleton v-else w="110px" h="22px" /></div>
          <div class="tile"><span class="lb">Kết quả</span><b v-if="data" class="num">{{ fmt(total.results) }}</b><Skeleton v-else w="60px" h="22px" /></div>
          <div class="tile"><span class="lb">CPA trung bình</span><b v-if="data" class="num">{{ total.cpa != null ? money(total.cpa, cur) : '–' }}</b><Skeleton v-else w="90px" h="22px" /></div>
          <div v-if="hasRevenue" class="tile"><span class="lb">ROAS</span><b class="num">{{ total.roas != null ? fmtDec(total.roas) : '–' }}</b></div>
        </div>

        <div class="bar">
          <Segmented v-model="metric" :options="METRICS" size="sm" />
          <span v-if="week" class="wk" :class="week.better == null ? '' : week.better ? 'good' : 'bad'" title="7 ngày gần nhất (không tính hôm nay) so với 7 ngày trước đó">
            <component :is="week.pct >= 0 ? TrendingUp : TrendingDown" :size="15" />
            {{ week.pct >= 0 ? '+' : '' }}{{ week.pct }}% so với 7 ngày trước
          </span>
          <Btn size="sm" variant="ghost" :icon="RefreshCw" :loading="loading && !!data" :disabled="!data" @click="load(true)">Làm mới</Btn>
        </div>

        <Callout v-if="data && data.stale" tone="warning">Facebook đang giới hạn số lần gọi, đây là số liệu tải lúc {{ timeOf(new Date(data.at).toISOString()) }}.</Callout>
        <Callout v-else-if="error" tone="danger">{{ error }}</Callout>

        <div ref="box" class="chart">
          <Skeleton v-if="!data" h="240px" r="12px" />
          <template v-else>
            <svg :width="W" :height="H" role="img" :aria-label="`Biểu đồ ${METRICS.find((m) => m.value === metric).label} theo ngày. Dùng phím mũi tên trái phải để xem từng ngày.`"
              tabindex="0" @mousemove="pick($event.currentTarget, $event.clientX)" @mouseleave="hover = null" @touchstart.passive="$event.touches[0] && pick($event.currentTarget, $event.touches[0].clientX)"
              @keydown="onKey" @blur="hover = null">
              <g class="grid">
                <template v-for="t in scale.ticks" :key="t">
                  <line :x1="PAD.l" :x2="W - PAD.r" :y1="y(t)" :y2="y(t)" :class="{ base: t === 0 }" />
                  <text :x="PAD.l - 8" :y="y(t) + 4" text-anchor="end">{{ tickText(t) }}</text>
                </template>
                <text v-for="l in xLabels" :key="l.i" :x="xMid(l.i)" :y="H - 6" text-anchor="middle">{{ l.text }}</text>
              </g>
              <rect v-if="hover != null" class="hl" :x="xMid(hover) - step / 2" :y="PAD.t" :width="step" :height="plotH" />
              <g v-if="!isLine" class="bars">
                <path v-for="(b, i) in bars" :key="i" :d="b.path" :class="{ today: b.today, dim: hover != null && hover !== i }" />
              </g>
              <g v-else class="line">
                <path :d="linePath" />
                <template v-for="(d, i) in dots" :key="i">
                  <circle v-if="d" :cx="d.x" :cy="d.y" :r="hover === i ? 5 : 3" :class="{ today: i === days.length - 1 }" />
                </template>
              </g>
              <g class="marks">
                <g v-for="m in markers" :key="m.i">
                  <line :x1="xMid(m.i)" :x2="xMid(m.i)" :y1="PAD.t" :y2="PAD.t + plotH" />
                  <circle :cx="xMid(m.i)" :cy="PAD.t - 10" r="5" :class="EV[m.list[m.list.length - 1].type].tone" />
                  <text v-if="m.list.length > 1" :x="xMid(m.i) + 8" :y="PAD.t - 6">{{ m.list.length }}</text>
                </g>
              </g>
            </svg>
            <div v-if="tipDay" class="tip" :style="{ left: tipLeft + 'px' }" role="status">
              <b>{{ dm(tipDay.date) }}{{ hover === days.length - 1 ? ' (hôm nay, chưa hết ngày)' : '' }}</b>
              <span><i>Chi tiêu</i>{{ money(tipDay.spend, cur) }}</span>
              <span><i>Kết quả</i>{{ fmt(tipDay.results) }}</span>
              <span><i>CPA</i>{{ tipDay.cpa != null ? money(tipDay.cpa, cur) : '–' }}</span>
              <span v-if="hasRevenue"><i>ROAS</i>{{ tipDay.roas != null ? fmtDec(tipDay.roas) : '–' }}</span>
              <span v-for="(e, k) in eventsByDate[tipDay.date] || []" :key="k" class="ev"><em :class="EV[e.type].tone" />{{ EV[e.type].label }}: {{ e.detail || e.source }}</span>
            </div>
          </template>
        </div>
        <p class="legend faint">
          <span><em class="danger" />Tắt</span><span><em class="success" />Bật</span><span><em class="info" />Đổi ngân sách</span>
          <span>{{ isLine ? "Điểm rỗng" : "Cột mờ" }} là hôm nay (chưa hết ngày).</span>
        </p>

        <section class="evs">
          <h4>Thay đổi trong 30 ngày</h4>
          <p v-if="data && !events.length" class="faint">Chưa có lần bật, tắt hay đổi ngân sách nào (không tính chạy thử).</p>
          <ul v-else-if="data">
            <li v-for="(e, k) in events" :key="k">
              <span class="when num">{{ dm(e.date) }} {{ timeOf(e.ts).slice(0, 5) }}</span>
              <span class="what" :class="EV[e.type].tone"><component :is="EV[e.type].icon" :size="14" />{{ EV[e.type].label }}</span>
              <span class="why">{{ e.detail }}<small class="faint"> · {{ e.source }}</small></span>
            </li>
          </ul>
        </section>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.tp { display: grid; gap: 14px; }
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; }
.tile { display: grid; gap: 4px; padding: 10px 12px; border-radius: 12px; background: var(--surface-2); border: 1px solid var(--border); }
.tile .lb { font-size: 12.5px; color: var(--text-3); }
.tile b { font-size: 18px; }
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.bar > :last-child { margin-left: auto; }
.wk { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; font-weight: 600; color: var(--text-2); }
.wk.good { color: var(--success); }
.wk.bad { color: var(--danger); }
.chart { position: relative; min-height: 240px; }
.chart svg { display: block; outline: none; touch-action: pan-y; }
.chart svg:focus-visible { box-shadow: 0 0 0 3px var(--accent-ring); border-radius: 8px; }
.grid line { stroke: var(--border); stroke-width: 1; }
.grid line.base { stroke: var(--border-strong); }
.grid text, .marks text { fill: var(--text-3); font-size: 11px; font-variant-numeric: tabular-nums; }
.hl { fill: var(--surface-3); opacity: .6; }
.bars path { fill: var(--accent); transition: opacity .15s; }
.bars path.today { opacity: .45; }
.bars path.dim { opacity: .55; }
.bars path.today.dim { opacity: .3; }
.line path { fill: none; stroke: var(--accent); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.line circle { fill: var(--accent); stroke: var(--surface); stroke-width: 2; }
.line circle.today { fill: var(--surface); stroke: var(--accent); }
.marks line { stroke: var(--text-3); stroke-width: 1; stroke-dasharray: 2 3; opacity: .7; }
.marks circle { stroke: var(--surface); stroke-width: 2; }
.danger { fill: var(--danger); color: var(--danger); }
.success { fill: var(--success); color: var(--success); }
.info { fill: var(--info); color: var(--info); }
.tip {
  position: absolute; top: 6px; transform: translateX(-50%); min-width: 190px; max-width: 260px; pointer-events: none; z-index: 2;
  display: grid; gap: 3px; padding: 9px 11px; border-radius: 10px; font-size: 12.5px;
  background: var(--surface); border: 1px solid var(--border-strong); box-shadow: var(--shadow-md);
}
.tip span { display: flex; justify-content: space-between; gap: 12px; font-variant-numeric: tabular-nums; }
.tip i { font-style: normal; color: var(--text-3); }
.tip .ev { justify-content: flex-start; gap: 6px; color: var(--text-2); border-top: 1px dashed var(--border); padding-top: 3px; }
em { display: inline-block; width: 9px; height: 9px; border-radius: 50%; flex: none; margin-top: 3px; background: currentColor; }
.legend { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 12.5px; margin: -4px 0 0; }
.legend span { display: inline-flex; align-items: center; gap: 5px; }
.legend em { margin-top: 0; }
.evs h4 { margin: 0 0 6px; font-size: 14px; }
.evs ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; max-height: 220px; overflow: auto; }
.evs li { display: grid; grid-template-columns: 92px 118px 1fr; gap: 8px; align-items: baseline; font-size: 13.5px; }
.what { display: inline-flex; align-items: center; gap: 5px; font-weight: 600; }
.why { color: var(--text-2); min-width: 0; overflow-wrap: anywhere; }
@media (max-width: 560px) {
  .evs li { grid-template-columns: 1fr; gap: 1px; padding-bottom: 6px; border-bottom: 1px solid var(--border); }
}
</style>
