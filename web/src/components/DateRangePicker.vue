<script setup>
// Bộ chọn khoảng ngày giống Ads Manager: danh sách khoảng có sẵn bên trái, lịch chọn khoảng tuỳ chọn bên phải.
//  v-model  : { preset } | { since, until } (xem shared/dates.mjs)
//  today    : hôm nay dạng 'YYYY-MM-DD' theo múi giờ đã cài
// Chọn khoảng có sẵn là áp dụng ngay; chọn trên lịch thì bấm "Áp dụng".
import { ref, computed, watch, h, defineAsyncComponent } from 'vue'
import { CalendarDays, ChevronDown, Check, Loader2 } from 'lucide-vue-next'
import Popover from './Popover.vue'
import Btn from './Btn.vue'
import { PRESETS, resolveRange, rangeLabel, minDate, parseRange, daysBetween, fmtDMY } from '../lib/dates'

const CalendarPane = defineAsyncComponent({
  loader: () => import('./CalendarPane.vue'),
  loadingComponent: { render: () => h('div', { class: 'cal-loading' }, 'Đang tải lịch…') },
  delay: 0,
})

const props = defineProps({ modelValue: { type: Object, required: true }, today: { type: String, required: true }, loading: Boolean })
const emit = defineEmits(['update:modelValue'])
const open = ref(false)
const months = ref(2)

const label = computed(() => rangeLabel(props.modelValue, props.today))
const custom = computed(() => !props.modelValue.preset)
const pad = (n) => String(n).padStart(2, '0')
const toDate = (iso) => { const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d) } // giờ máy, không lệch múi giờ
const toISO = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const minD = computed(() => toDate(minDate(props.today)))
const maxD = computed(() => toDate(props.today))

// Khoảng đang chọn trên lịch (chưa áp dụng)
const draft = ref(null)
const pending = ref(null) // ngày bắt đầu vừa bấm, đang chờ ngày kết thúc
watch(open, (v) => {
  pending.value = null
  if (!v) return
  months.value = innerWidth >= 780 ? 2 : 1
  const r = resolveRange(props.modelValue, props.today)
  draft.value = r.since ? [toDate(r.since), toDate(r.until)] : null // "Tối đa" quá dài để tô trên lịch
})
const draftSpec = computed(() => {
  const d = draft.value
  if (!d || !d[0] || !d[1]) return null
  const a = toISO(d[0]), b = toISO(d[1])
  return { since: a <= b ? a : b, until: a <= b ? b : a }
})
const draftText = computed(() => {
  if (pending.value) return `Từ ${fmtDMY(toISO(pending.value))} · chọn ngày kết thúc`
  const s = draftSpec.value
  if (!s) return 'Chọn ngày bắt đầu và ngày kết thúc'
  const n = daysBetween(s.since, s.until)
  return s.since === s.until ? `${fmtDMY(s.since)} · 1 ngày` : `${fmtDMY(s.since)} – ${fmtDMY(s.until)} · ${n} ngày`
})
// chỉ cho áp dụng khi khoảng chọn trên lịch khác khoảng đang xem (kể cả khi đang xem 1 khoảng có sẵn như '7 ngày qua')
const changed = computed(() => {
  const s = draftSpec.value
  if (!s || pending.value) return false
  const cur = resolveRange(props.modelValue, props.today)
  return !(cur.since === s.since && cur.until === s.until)
})

function pick(id, close) { close(); if (props.modelValue.preset !== id) emit('update:modelValue', { preset: id }) }
function apply(close) {
  const r = parseRange(draftSpec.value || {}, props.today)
  if (!r.ok) return
  close(); emit('update:modelValue', r.spec)
}
const hint = (id) => { const r = resolveRange({ preset: id }, props.today); return r.since ? (r.since === r.until ? fmtDMY(r.since) : `${fmtDMY(r.since)} – ${fmtDMY(r.until)}`) : `đến ${fmtDMY(r.until)}` }
</script>

<template>
  <Popover v-model="open" label="Chọn khoảng ngày">
    <template #trigger="{ toggle }">
      <button type="button" class="dbtn" :class="{ on: open }" aria-haspopup="dialog" :aria-expanded="open" @click="toggle">
        <CalendarDays :size="17" class="ci" />
        <span class="dt"><b>{{ label.title }}</b><em>{{ label.dates }}</em></span>
        <Loader2 v-if="loading" :size="15" class="spin ch" />
        <ChevronDown v-else :size="15" class="ch" />
      </button>
    </template>

    <template #default="{ close }">
      <div class="drp">
        <ul class="presets" role="listbox" aria-label="Khoảng có sẵn">
          <li v-for="p in PRESETS" :key="p.id">
            <button type="button" role="option" :aria-selected="modelValue.preset === p.id" :class="{ on: modelValue.preset === p.id }" :title="hint(p.id)" @click="pick(p.id, close)">
              <span>{{ p.label }}</span><Check v-if="modelValue.preset === p.id" :size="15" />
            </button>
          </li>
          <li class="cu"><span :class="{ on: custom }">Tuỳ chọn{{ custom ? ': ' + label.dates : '' }}</span></li>
        </ul>
        <div class="cal">
          <CalendarPane v-model="draft" :min="minD" :max="maxD" :months="months" @start="(d) => (pending = d)" @update:model-value="pending = null" />
          <footer class="cf">
            <span class="sel" :class="{ empty: !draftSpec && !pending }">{{ draftText }}</span>
            <span class="acts"><Btn size="sm" @click="close()">Huỷ</Btn><Btn size="sm" variant="primary" :disabled="!changed" @click="apply(close)">Áp dụng</Btn></span>
          </footer>
        </div>
      </div>
    </template>
  </Popover>
</template>

<style scoped>
.dbtn {
  display: inline-flex; align-items: center; gap: 10px; min-height: 42px; padding: 6px 12px 6px 12px; border-radius: 12px; cursor: pointer;
  background: var(--surface); border: 1px solid var(--border-strong); color: var(--text); font: inherit; text-align: left; box-shadow: var(--shadow-sm);
  transition: border-color .15s, box-shadow .15s;
}
.dbtn:hover, .dbtn.on { border-color: var(--accent); }
.dbtn.on { box-shadow: 0 0 0 4px var(--accent-soft); }
.ci { color: var(--accent); flex: none; } .ch { color: var(--text-3); flex: none; }
.dt { display: flex; flex-direction: column; line-height: 1.2; min-width: 0; }
.dt b { font-size: 14px; font-weight: 650; white-space: nowrap; }
.dt em { font-style: normal; font-size: 12px; color: var(--text-3); white-space: nowrap; }

.drp { display: flex; min-width: 0; }
.presets { list-style: none; margin: 0; padding: 10px; width: 216px; flex: none; border-right: 1px solid var(--border); display: flex; flex-direction: column; gap: 2px; background: var(--surface-2); }
.presets button { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 8px; border: 0; background: none; color: var(--text-2); font: inherit; font-size: 14px; padding: 8px 11px; border-radius: 9px; cursor: pointer; text-align: left; }
.presets button:hover { background: var(--surface-3); color: var(--text); }
.presets button.on { background: var(--accent-soft); color: var(--accent); font-weight: 650; }
.cu { margin-top: 6px; padding: 8px 11px 4px; border-top: 1px solid var(--border); font-size: 13px; color: var(--text-3); }
.cu .on { color: var(--accent); font-weight: 650; }
.cal { padding: 8px 10px 0; min-width: 0; display: flex; flex-direction: column; }
.cf { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 10px 6px 12px; margin-top: 4px; border-top: 1px solid var(--border); }
.sel { font-size: 13.5px; font-weight: 600; } .sel.empty { color: var(--text-3); font-weight: 500; }
.acts { display: inline-flex; gap: 8px; }
:deep(.cal-loading) { display: grid; place-items: center; min-height: 290px; min-width: 300px; color: var(--text-3); font-size: 14px; }

@media (max-width: 640px) {
  .dbtn { width: 100%; }
  .drp { flex-direction: column; }
  .presets { width: auto; border-right: 0; border-bottom: 1px solid var(--border); flex-direction: row; flex-wrap: nowrap; overflow-x: auto; padding: 10px; gap: 6px; }
  .presets li { flex: none; } .presets button { width: auto; white-space: nowrap; border: 1px solid var(--border); padding: 7px 12px; }
  .cu { display: none; }
  .cal { padding: 4px 6px 0; align-items: center; }
  .cf { width: 100%; }
}
</style>
