<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Check, Plus, X } from 'lucide-vue-next'
import { api } from '../lib/api'
import { DAY_LABEL, DAY_ORDER } from '../lib/constants'
import { validateSchedule, scheduleTimes, isTime } from '../lib/validate'
import { CONDS, parseMoney, matchFilter, nextBudget } from '../lib/bulkBudget'
import { fmt } from '../lib/format'
import { state } from '../stores/app'
import { toast } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'
import TargetPicker from './TargetPicker.vue'

const props = defineProps({ modelValue: Boolean, item: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])

// filter.x / filter.y giữ nguyên chữ người gõ (vd "100k"), đổi sang số khi kiểm tra và lưu
const blankFilter = () => ({ level: 'campaign', op: 'lt', x: '', y: '', name: '', onlyRunning: false })
const blank = () => ({ name: '', action: 'on', times: ['06:00'], days: [0, 1, 2, 3, 4, 5, 6], targetMode: 'list', targets: [], filter: blankFilter(), mode: 'percent', value: 20, enabled: true })
const f = ref(blank())
const submitted = ref(false)
const touched = reactive({})

watch(() => props.modelValue, (open) => {
  if (!open) return
  const it = JSON.parse(JSON.stringify(props.item || {}))
  const times = scheduleTimes(it) // lịch cũ/mẫu tạo nhanh chỉ có `time`
  const fl = it.filter || {}
  f.value = { ...blank(), ...it, times: times.length ? times : blank().times,
    filter: { ...blankFilter(), ...fl, x: fl.x != null ? String(fl.x) : '', y: fl.y != null ? String(fl.y) : '' } }
  delete f.value.time
  newTime.value = ''
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
})

// Kiểm tra theo thời gian thực bằng đúng luật của server
const objs = computed(() => (state.objsLoaded && state.objs.length ? state.objs : null))
const payload = computed(() => {
  const v = { ...f.value }
  if (v.targetMode === 'filter') v.filter = { ...v.filter, x: parseMoney(v.filter.x), y: parseMoney(v.filter.y) }
  else delete v.filter
  return v
})
const check = computed(() => validateSchedule(payload.value, { objs: objs.value, schedules: state.schedules }))
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')
const showTargets = computed(() => (submitted.value || f.value.targets.length ? check.value.errors.targets : ''))

// Theo điều kiện: xem trước các mục đang khớp NGAY BÂY GIỜ (lúc chạy tool lọc lại theo số liệu khi đó)
const hasAdsets = computed(() => state.objs.some((o) => o.level === 'adset'))
const targetModes = [{ value: 'list', label: 'Chọn từng mục' }, { value: 'filter', label: 'Theo điều kiện' }]
const moneyHint = (v) => { const n = parseMoney(v); return v !== '' && Number.isFinite(n) ? fmt(n) : '' }
const preview = computed(() => {
  if (f.value.targetMode !== 'filter' || check.value.errors.filter || !state.objsLoaded) return null
  let list = matchFilter(state.objs, payload.value.filter)
  const isBudget = f.value.action === 'budget', val = Number(f.value.value)
  let cbo = 0
  if (isBudget) { cbo = list.filter((o) => o.dailyBudget == null).length; list = list.filter((o) => o.dailyBudget != null) }
  const items = list.map((o) => ({ o, to: isBudget && Number.isFinite(val) ? nextBudget(o.dailyBudget, { mode: f.value.mode, value: val }) : null }))
  return { items, cbo }
})

const toggleDay = (d) => { const s = new Set(f.value.days); s.has(d) ? s.delete(d) : s.add(d); f.value.days = [...s]; touched.days = true }
const setDays = (arr) => { f.value.days = arr; touched.days = true }

// Nhiều giờ chạy trong ngày
const newTime = ref('')
const rep = reactive({ every: 2, from: '08:00', to: '22:00' })
const sortedTimes = computed(() => [...new Set(f.value.times)].sort())
const toMin = (s) => { const [h, m] = s.split(':').map(Number); return h * 60 + m }
const hhmm = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
function addTime() {
  const t = newTime.value
  if (!isTime(t)) return toast('Chọn giờ cần thêm', 'error')
  if (!f.value.times.includes(t)) f.value.times = [...f.value.times, t]
  newTime.value = ''; touched.time = true
}
const removeTime = (t) => { f.value.times = f.value.times.filter((x) => x !== t); touched.time = true }
function genTimes() {
  const every = Number(rep.every)
  if (!(every >= 0.5 && every <= 12) || !isTime(rep.from) || !isTime(rep.to)) return toast('Nhập số giờ lặp (0,5 đến 12) và khung giờ hợp lệ', 'error')
  const out = []
  for (let m = toMin(rep.from); m <= toMin(rep.to); m += Math.round(every * 60)) out.push(hhmm(m))
  if (!out.length) return toast('Giờ bắt đầu phải trước giờ kết thúc', 'error')
  f.value.times = out; touched.time = true
  toast(`Đã tạo ${out.length} giờ chạy`)
}

async function save() {
  submitted.value = true
  const r = check.value
  if (!r.ok) {
    toast(`Còn ${Object.keys(r.errors).length} mục cần sửa`, 'error')
    await nextTick()
    const el = document.querySelector('.sheet .invalid, .sheet .co.danger')
    if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    return
  }
  await api('schedules', 'POST', r.value)
  toast('Đã lưu lịch')
  emit('saved'); emit('update:modelValue', false)
}
const actions = [{ value: 'on', label: 'Bật camp' }, { value: 'off', label: 'Tắt camp' }, { value: 'budget', label: 'Đổi ngân sách' }]
const modes = [{ value: 'percent', label: 'Theo %' }, { value: 'set', label: 'Số tiền cố định' }, { value: 'add', label: 'Cộng/trừ số tiền' }]
const valueHint = computed(() => (f.value.mode === 'percent' ? 'Nhập số âm để giảm (vd -30).' : f.value.mode === 'add' ? 'Số âm để trừ (vd -50000). ' + (moneyHint(f.value.value) ? `= ${moneyHint(f.value.value)}` : '') : `Đặt ngân sách ngày đúng bằng số này. ${moneyHint(f.value.value) ? `= ${moneyHint(f.value.value)}` : ''}`))
</script>

<template>
  <Modal :model-value="modelValue" :title="item && item.id ? 'Sửa lịch' : 'Thêm lịch'" subtitle="Tool sẽ chạy đúng giờ, kể cả khi bạn không mở trang này." @update:model-value="emit('update:modelValue', $event)">
    <Field label="Tên lịch" :error="show('name')"><input v-model="f.name" class="input" maxlength="120" placeholder="Vd: Bật camp buổi sáng" @input="touched.name = true" /></Field>

    <Field label="Hành động"><Segmented v-model="f.action" :options="actions" block /></Field>

    <Field label="Giờ chạy" tip="scheduleTime" :error="show('time')" hint="Thêm nhiều giờ trong ngày (tối đa 24), mỗi giờ chạy 1 lần. Giờ nào hôm nay chưa tới thì chạy luôn trong hôm nay.">
      <div class="times">
        <span v-for="t in sortedTimes" :key="t" class="tchip num">{{ t }}<button type="button" :aria-label="'Bỏ giờ ' + t" @click="removeTime(t)"><X :size="13" /></button></span>
        <span class="add"><input v-model="newTime" type="time" class="input tin" aria-label="Giờ cần thêm" @keydown.enter.prevent="addTime" /><Btn size="sm" :icon="Plus" @click="addTime">Thêm giờ</Btn></span>
      </div>
      <details class="rep"><summary>Lặp lại trong ngày</summary>
        <div class="inl">Mỗi <input v-model="rep.every" type="number" min="0.5" max="12" step="0.5" class="input sm" aria-label="Số giờ lặp" /> giờ, từ <input v-model="rep.from" type="time" class="input tin" aria-label="Từ giờ" /> đến <input v-model="rep.to" type="time" class="input tin" aria-label="Đến giờ" /><Btn size="sm" @click="genTimes">Tạo các giờ</Btn></div>
        <small class="faint">Danh sách giờ ở trên sẽ được thay bằng các giờ vừa tạo.</small>
      </details>
    </Field>

    <Field v-if="f.action === 'budget'" label="Đổi ngân sách" tip="scheduleBudget" :error="show('value')" :hint="valueHint">
      <div class="inl"><Segmented v-model="f.mode" :options="modes" /><input v-model="f.value" type="number" step="any" class="input val" @input="touched.value = true" /></div>
    </Field>

    <Field label="Ngày chạy" :error="show('days')">
      <div class="days">
        <button v-for="d in DAY_ORDER" :key="d" type="button" class="day" :class="{ on: f.days.includes(d) }" @click="toggleDay(d)">{{ DAY_LABEL[d] }}</button>
      </div>
      <div class="quick">
        <button type="button" @click="setDays([0, 1, 2, 3, 4, 5, 6])">Hằng ngày</button>
        <button type="button" @click="setDays([1, 2, 3, 4, 5])">T2–T6</button>
        <button type="button" @click="setDays([6, 0])">Cuối tuần</button>
      </div>
    </Field>

    <Field label="Áp dụng cho" :error="f.targetMode === 'list' ? showTargets : show('filter')">
      <Segmented v-model="f.targetMode" :options="targetModes" size="sm" class="tm" />
      <TargetPicker v-if="f.targetMode === 'list'" v-model="f.targets" :need-budget="f.action === 'budget'" />
      <div v-else class="flt">
        <Segmented v-if="hasAdsets" v-model="f.filter.level" :options="[{ value: 'campaign', label: 'Chiến dịch' }, { value: 'adset', label: 'Nhóm quảng cáo' }]" size="sm" />
        <div class="inl top">
          <span class="lb">Ngân sách/ngày</span>
          <select v-model="f.filter.op" class="input op" @change="touched.filter = true"><option v-for="(c, k) in CONDS" :key="k" :value="k">{{ c.label }}</option></select>
          <template v-if="f.filter.op !== 'any'">
            <span class="mi"><input v-model="f.filter.x" class="input" inputmode="decimal" placeholder="vd 100k" aria-label="Mức ngân sách" @input="touched.filter = true" /><small class="faint">{{ moneyHint(f.filter.x) }}</small></span>
            <template v-if="f.filter.op === 'between'"><span class="faint">và</span><span class="mi"><input v-model="f.filter.y" class="input" inputmode="decimal" placeholder="vd 300k" aria-label="Mức thứ hai" @input="touched.filter = true" /><small class="faint">{{ moneyHint(f.filter.y) }}</small></span></template>
          </template>
        </div>
        <div class="inl">
          <input v-model="f.filter.name" class="input nmf" placeholder="Tên chứa… (không bắt buộc)" aria-label="Tên chứa" />
          <label class="chk"><input v-model="f.filter.onlyRunning" type="checkbox" /> Chỉ mục đang chạy</label>
        </div>
        <div v-if="preview" class="pv">
          <p class="pvh"><b>Hiện có {{ preview.items.length }} mục khớp</b><span v-if="preview.cbo" class="faint"> · bỏ qua {{ preview.cbo }} mục dùng ngân sách cấp khác (CBO)</span></p>
          <ul v-if="preview.items.length">
            <li v-for="i in preview.items.slice(0, 8)" :key="i.o.id"><span class="nm" :title="i.o.name">{{ i.o.name }}</span>
              <span v-if="i.to != null" class="num faint">{{ fmt(i.o.dailyBudget) }} → <b>{{ fmt(i.to) }}</b></span></li>
            <li v-if="preview.items.length > 8" class="faint">… và {{ preview.items.length - 8 }} mục khác</li>
          </ul>
          <small class="faint">Mỗi lần chạy, tool lọc lại theo số liệu lúc đó: mục mới tạo cũng được áp dụng nếu khớp.</small>
        </div>
      </div>
    </Field>

    <Callout v-if="submitted && check.errors.conflict" tone="danger">{{ check.errors.conflict }}</Callout>
    <Callout v-for="w in check.warnings" :key="w" tone="warning">{{ w }}</Callout>

    <template #footer>
      <Btn @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :action="save">Lưu lịch</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.times { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.tchip { display: inline-flex; align-items: center; gap: 4px; padding: 6px 6px 6px 12px; border-radius: 10px; background: var(--accent-soft); color: var(--accent); font-weight: 650; font-size: 14px; }
.tchip button { border: 0; background: none; color: inherit; display: grid; place-items: center; padding: 3px; border-radius: 6px; }
.tchip button:hover { background: var(--accent); color: #fff; }
.add { display: inline-flex; gap: 6px; align-items: center; }
.tin { width: 120px; } .sm { width: 80px; }
.rep { margin-top: 10px; } .rep summary { cursor: pointer; font-weight: 600; font-size: 13.5px; color: var(--accent); }
.rep .inl { margin: 10px 0 6px; font-size: 14px; }
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.val { width: 140px; }
.tm { margin-bottom: 10px; }
.flt { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--border); border-radius: 14px; }
.inl.top { align-items: flex-start; } .lb { font-size: 14px; color: var(--text-2); height: 40px; display: inline-flex; align-items: center; }
.op { width: auto; }
.mi { display: inline-flex; flex-direction: column; gap: 3px; } .mi .input { width: 140px; } .mi small { font-size: 12px; min-height: 15px; }
.nmf { flex: 1; min-width: 180px; }
.chk { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; cursor: pointer; } .chk input { accent-color: var(--accent); width: 16px; height: 16px; }
.pv { background: var(--surface-2); border-radius: 11px; padding: 10px 12px; font-size: 13.5px; }
.pvh { margin: 0 0 6px; }
.pv ul { list-style: none; margin: 0 0 6px; padding: 0; display: grid; gap: 4px; }
.pv li { display: flex; justify-content: space-between; gap: 10px; }
.pv .nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.pv li .num { white-space: nowrap; } .pv li b { color: var(--text); }
.days { display: flex; gap: 6px; flex-wrap: wrap; }
.day { width: 46px; height: 40px; border-radius: 12px; border: 1px solid var(--border-strong); background: var(--surface); font-weight: 650; font-size: 13.5px; color: var(--text-2); transition: .15s var(--ease); }
.day:hover { border-color: var(--accent); color: var(--accent); }
.day.on { background: var(--accent-grad); border-color: transparent; color: #fff; box-shadow: 0 4px 12px -4px var(--accent-ring); }
.quick { display: flex; gap: 4px; margin-top: 8px; }
.quick button { border: 0; background: none; color: var(--accent); font-weight: 600; font-size: 13px; padding: 4px 8px; border-radius: 7px; }
.quick button:hover { background: var(--accent-soft); }
@media (max-width: 520px) { .day { width: 42px; } }
</style>
