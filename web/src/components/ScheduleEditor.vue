<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Check, Plus, X, CircleAlert } from 'lucide-vue-next'
import { api } from '../lib/api'
import { DAY_LABEL, DAY_ORDER } from '../lib/constants'
import { validateSchedule, scheduleTimes, isTime } from '../lib/validate'
import { parseMoney, budgetChange } from '../lib/bulkBudget'
import { fmt } from '../lib/format'
import { state } from '../stores/app'
import { toast } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'
import FilterPicker from './FilterPicker.vue'

const props = defineProps({ modelValue: Boolean, item: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])

// filter.x / filter.y giữ nguyên chữ người gõ (vd "100k"), đổi sang số khi kiểm tra và lưu
const blankFilter = () => ({ level: 'campaign', op: 'any', x: '', y: '', name: '', onlyRunning: false, account: '' })
const blank = () => ({ name: '', action: 'on', times: ['06:00'], days: [0, 1, 2, 3, 4, 5, 6], targetMode: 'list', targets: [], filter: blankFilter(), exclude: [], mode: 'percent', value: 20, enabled: true })
const f = ref(blank())
const submitted = ref(false)
const touched = reactive({})
const startOnlySel = ref(false) // sửa lịch đã chọn sẵn mục: mở danh sách ở chế độ "chỉ hiện mục đã chọn"

watch(() => props.modelValue, (open) => {
  if (!open) return
  const it = JSON.parse(JSON.stringify(props.item || {}))
  const times = scheduleTimes(it) // lịch cũ/mẫu tạo nhanh chỉ có `time`
  const fl = it.filter || {}
  f.value = { ...blank(), ...it, times: times.length ? times : blank().times,
    filter: { ...blankFilter(), ...fl, x: fl.x != null ? String(fl.x) : '', y: fl.y != null ? String(fl.y) : '' } }
  delete f.value.time
  startOnlySel.value = !!(it.id && it.targetMode !== 'filter' && (it.targets || []).length)
  newTime.value = ''
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
})

// Kiểm tra theo thời gian thực bằng đúng luật của server
const objs = computed(() => (state.objsLoaded && state.objs.length ? state.objs : null))
const payload = computed(() => {
  const v = { ...f.value }
  if (v.targetMode === 'filter') v.filter = { ...v.filter, x: parseMoney(v.filter.x), y: parseMoney(v.filter.y) }
  else { delete v.filter; delete v.exclude }
  return v
})
const check = computed(() => validateSchedule(payload.value, { objs: objs.value, schedules: state.schedules }))
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')
const showTargets = computed(() => (submitted.value || f.value.targets.length ? check.value.errors.targets : ''))

// Áp dụng cho: lọc rồi tick chọn (lưu danh sách cố định) hoặc tự động theo điều kiện (lọc lại mỗi lần chạy)
const targetModes = [
  { value: 'list', label: 'Chọn chiến dịch', desc: 'Lọc rồi tích chọn. Lịch chỉ áp dụng cho đúng các mục đã tích.' },
  { value: 'filter', label: 'Tự động theo điều kiện', desc: 'Mọi mục khớp lúc chạy, kể cả mục mới tạo. Bỏ tích để loại trừ mục không muốn.' },
]
const targetErr = computed(() => (f.value.targetMode === 'list' ? showTargets.value : submitted.value ? check.value.errors.filter || '' : ''))
const moneyHint = (v) => { const n = parseMoney(v); return v !== '' && v != null && Number.isFinite(n) ? fmt(n) : '' }
// Cột "Ngân sách mới" trong danh sách khi hành động là đổi ngân sách
const budgetPreview = computed(() => {
  const v = f.value
  if (v.action !== 'budget' || v.value === '' || v.value == null || !Number.isFinite(Number(v.value))) return null
  const action = { mode: v.mode, value: Number(v.value) }
  return (o) => budgetChange(o, action)
})

const toggleDay = (d) => { const s = new Set(f.value.days); s.has(d) ? s.delete(d) : s.add(d); f.value.days = [...s]; touched.days = true }
const setDays = (arr) => { f.value.days = arr; touched.days = true }

// Nhiều giờ chạy trong ngày
const newTime = ref('')
const sortedTimes = computed(() => [...new Set(f.value.times)].sort())
function addTime() {
  const t = newTime.value
  if (!isTime(t)) return toast('Chọn giờ cần thêm', 'error')
  if (!f.value.times.includes(t)) f.value.times = [...f.value.times, t]
  newTime.value = ''; touched.time = true
}
const removeTime = (t) => { f.value.times = f.value.times.filter((x) => x !== t); touched.time = true }

async function save() {
  // đã chọn giờ trong ô nhưng quên bấm "Thêm giờ" → tự thêm
  if (isTime(newTime.value) && !f.value.times.includes(newTime.value)) { f.value.times = [...f.value.times, newTime.value]; newTime.value = '' }
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

    <Field label="Áp dụng cho">
      <div class="modes" role="radiogroup" aria-label="Cách chọn mục áp dụng">
        <button v-for="m in targetModes" :key="m.value" type="button" role="radio" class="mode" :class="{ on: f.targetMode === m.value }" :aria-checked="f.targetMode === m.value" @click="f.targetMode = m.value">
          <span class="rd" /><span class="mt"><b>{{ m.label }}</b><small>{{ m.desc }}</small></span>
        </button>
      </div>
      <FilterPicker v-model="f.targets" v-model:filter="f.filter" v-model:exclude="f.exclude" :auto="f.targetMode === 'filter'" keep-hidden :need-budget="f.action === 'budget'" :change="budgetPreview" :start-only-selected="startOnlySel" />
      <p v-if="targetErr" class="terr" role="alert"><CircleAlert :size="14" /><span>{{ targetErr }}</span></p>
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
.tin { width: 120px; }
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.val { width: 140px; }
/* Áp dụng cho: 2 thẻ lựa chọn */
.modes { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 12px; }
.mode { display: flex; gap: 11px; align-items: flex-start; text-align: left; padding: 12px 14px; border: 1.5px solid var(--border); border-radius: 14px; background: var(--surface); color: inherit; font: inherit; cursor: pointer; transition: border-color .15s, background .15s; }
.mode:hover { border-color: var(--border-strong); }
.mode.on { border-color: var(--accent); background: var(--accent-soft); }
.rd { width: 18px; height: 18px; border-radius: 50%; border: 2px solid var(--border-strong); flex: none; margin-top: 1px; display: grid; place-items: center; }
.mode.on .rd { border-color: var(--accent); }
.mode.on .rd::after { content: ''; width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
.mt { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.mt b { font-size: 14.5px; font-weight: 650; }
.mt small { font-size: 12.5px; color: var(--text-2); line-height: 1.45; }
.terr { display: flex; gap: 6px; align-items: flex-start; color: var(--danger); font-size: 13px; margin: 8px 0 0; }
.terr svg { flex: none; margin-top: 2px; }
.days { display: flex; gap: 6px; flex-wrap: wrap; }
.day { width: 46px; height: 40px; border-radius: 12px; border: 1px solid var(--border-strong); background: var(--surface); font-weight: 650; font-size: 13.5px; color: var(--text-2); transition: .15s var(--ease); }
.day:hover { border-color: var(--accent); color: var(--accent); }
.day.on { background: var(--accent-grad); border-color: transparent; color: #fff; box-shadow: 0 4px 12px -4px var(--accent-ring); }
.quick { display: flex; gap: 4px; margin-top: 8px; }
.quick button { border: 0; background: none; color: var(--accent); font-weight: 600; font-size: 13px; padding: 4px 8px; border-radius: 7px; }
.quick button:hover { background: var(--accent-soft); }
@media (max-width: 560px) { .day { width: 42px; } .modes { grid-template-columns: 1fr; } }
</style>
