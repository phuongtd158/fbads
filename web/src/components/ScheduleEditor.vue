<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Check, Plus, X, CircleAlert, Power, CircleOff, Clock, Wallet, Sparkles } from 'lucide-vue-next'
import { api } from '../lib/api'
import { DAY_LABEL, DAY_ORDER } from '../lib/constants'
import { validateSchedule, scheduleTimes, scheduleEvents, isTime } from '../lib/validate'
import { parseMoney, budgetChange, matchFilter } from '../lib/bulkBudget'
import { scheduleName, describeSchedule, daysPreset, daysText, timesText } from '../lib/names'
import { state } from '../stores/app'
import { toast } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'
import FilterPicker from './FilterPicker.vue'
import MoneyInput from './MoneyInput.vue'

const props = defineProps({ modelValue: Boolean, item: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])

// filter.x / filter.y là số tiền người gõ (có thể còn trống), đổi sang số khi kiểm tra và lưu
const blankFilter = () => ({ level: 'campaign', op: 'any', x: '', y: '', name: '', status: 'all', onlyRunning: false, account: '' })
const blankWindow = () => ({ on: '06:00', off: '23:00' })
const blank = () => ({ name: '', action: 'on', times: ['06:00'], window: blankWindow(), days: [0, 1, 2, 3, 4, 5, 6], targetMode: 'list', targets: [], filter: blankFilter(), exclude: [], mode: 'percent', value: 20, enabled: true })
const f = ref(blank())
const submitted = ref(false)
const touched = reactive({})
const startOnlySel = ref(false) // sửa lịch đã chọn sẵn mục: mở danh sách ở chế độ "chỉ hiện mục đã chọn"

watch(() => props.modelValue, (open) => {
  if (!open) return
  const it = JSON.parse(JSON.stringify(props.item || {}))
  const times = scheduleTimes(it) // lịch cũ/mẫu tạo nhanh chỉ có `time`
  const fl = it.filter || {}
  f.value = { ...blank(), ...it, times: times.length ? times : blank().times, window: { ...blankWindow(), ...(it.window || {}) },
    filter: { ...blankFilter(), ...fl, status: fl.status || (fl.onlyRunning ? 'running' : 'all'), x: fl.x != null ? String(fl.x) : '', y: fl.y != null ? String(fl.y) : '' } }
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
  if (v.action !== 'window') delete v.window
  if (!String(v.name || '').trim()) v.name = scheduleName(v) // để trống tên → tự đặt theo nội dung
  return v
})
const autoName = computed(() => scheduleName(f.value))
const check = computed(() => validateSchedule(payload.value, { objs: objs.value, schedules: state.schedules }))
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')
const showTargets = computed(() => (submitted.value || f.value.targets.length ? check.value.errors.targets : ''))

// Áp dụng cho: lọc rồi tick chọn (lưu danh sách cố định) hoặc tự động theo điều kiện (lọc lại mỗi lần chạy)
const targetModes = [
  { value: 'list', label: 'Chọn từng mục', desc: 'Lịch chỉ áp dụng cho đúng các mục bạn tích.' },
  { value: 'filter', label: 'Tự động theo điều kiện', desc: 'Áp dụng cho mọi mục khớp lúc chạy, kể cả mục mới tạo. Bỏ tích để loại trừ.' },
]
const modeDesc = computed(() => targetModes.find((m) => m.value === f.value.targetMode).desc)
// Số mục sẽ áp dụng (câu tóm tắt + thanh dưới)
const applied = computed(() => {
  const v = payload.value
  if (v.targetMode !== 'filter') {
    const lv = new Set(v.targets.map((id) => (state.objs.find((o) => o.id === id) || {}).level))
    return { count: v.targets.length, unit: lv.size === 1 && lv.has('adset') ? 'nhóm QC' : 'chiến dịch', auto: false }
  }
  const ex = new Set(v.exclude || [])
  let list = matchFilter(state.objs, v.filter || {}).filter((o) => !ex.has(o.id))
  if (v.action === 'budget') list = list.filter((o) => o.dailyBudget != null)
  return { count: list.length, unit: v.filter.level === 'adset' ? 'nhóm QC' : 'chiến dịch', auto: true }
})
const summary = computed(() => describeSchedule(f.value, applied.value))
const whenShort = computed(() => {
  const v = f.value, d = daysText(v.days)
  const at = v.action === 'window' ? `${v.window.on || '…'} → ${v.window.off || '…'}` : timesText(v.times) || 'Chưa có giờ'
  return d ? `${at} · ${d}` : at
})
const targetErr = computed(() => (f.value.targetMode === 'list' ? showTargets.value : submitted.value ? check.value.errors.filter || '' : ''))
// Cột "Ngân sách mới" trong danh sách khi hành động là đổi ngân sách
const budgetPreview = computed(() => {
  const v = f.value
  if (v.action !== 'budget' || v.value === '' || v.value == null || !Number.isFinite(Number(v.value))) return null
  const action = { mode: v.mode, value: Number(v.value) }
  return (o) => budgetChange(o, action)
})

// Lọc theo ngân sách chỉ dùng khi đổi ngân sách: đổi sang bật/tắt thì bỏ điều kiện ngân sách (đang bị ẩn)
watch(() => f.value.action, (a, prev) => {
  if (a !== 'budget' && f.value.filter.op !== 'any') f.value.filter.op = 'any'
  // khung giờ theo điều kiện: lúc bật và lúc tắt phải lọc ra cùng danh sách → không lọc theo trạng thái
  if (a === 'window' && prev && f.value.filter.status !== 'all') { f.value.filter.status = 'all'; f.value.filter.onlyRunning = false }
})

// Nhãn các lịch KHÁC đang tác động lên từng mục (hiện trên dòng trong danh sách chọn) để tránh tạo trùng / ngược nhau
const shortTimes = (ts) => (ts.length > 2 ? `${ts.slice(0, 2).join(', ')}…` : ts.join(', '))
function scheduleTag(s) {
  if (s.action === 'window') return { text: `Bật ${s.window.on} · Tắt ${s.window.off}`, tone: 'success' }
  const at = shortTimes(scheduleTimes(s))
  if (s.action === 'on') return { text: `Bật ${at}`, tone: 'success' }
  if (s.action === 'off') return { text: `Tắt ${at}`, tone: 'danger' }
  return { text: `Ngân sách ${at}`, tone: 'info' }
}
const otherTags = computed(() => {
  const m = {}
  if (!props.modelValue) return m
  for (const s of state.schedules) {
    if (!s.enabled || (props.item && s.id === props.item.id) || !scheduleEvents(s).length) continue
    const tag = scheduleTag(s)
    const ex = new Set(s.exclude || [])
    const ids = s.targetMode === 'filter' ? matchFilter(state.objs, s.filter || {}).filter((o) => !ex.has(o.id)).map((o) => o.id) : s.targets || []
    for (const id of ids) (m[id] ||= []).push({ ...tag, text: s.targetMode === 'filter' ? `${tag.text} (điều kiện)` : tag.text })
  }
  return m
})
const NO_TAGS = []
const tagsOf = (id) => otherTags.value[id] || NO_TAGS

const preset = computed(() => daysPreset(f.value.days))
const toggleDay = (d) => { const s = new Set(f.value.days); s.has(d) ? s.delete(d) : s.add(d); f.value.days = [...s]; touched.days = true }
const setDays = (arr) => { f.value.days = arr; touched.days = true }

// Nhiều giờ chạy trong ngày
const newTime = ref('')
// Bấm "Thêm giờ" → hiện ô giờ (mở luôn bảng chọn giờ nếu trình duyệt hỗ trợ); chọn xong là thêm
const adding = ref(false)
const timeEl = ref(null)
async function startAdd() {
  adding.value = true
  await nextTick()
  const el = timeEl.value
  if (!el) return
  el.focus()
  try { el.showPicker && el.showPicker() } catch { /* một số trình duyệt chặn showPicker */ }
}
function onTimeChange() { if (isTime(newTime.value)) { addTime(); adding.value = false } }
function onTimeBlur() { if (isTime(newTime.value)) addTime(); adding.value = false }
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
const actions = [
  { value: 'on', label: 'Bật camp', desc: 'Bật lúc giờ đã hẹn', icon: Power },
  { value: 'off', label: 'Tắt camp', desc: 'Tắt lúc giờ đã hẹn', icon: CircleOff },
  { value: 'window', label: 'Bật rồi tắt', desc: 'Chạy trong một khung giờ', icon: Clock },
  { value: 'budget', label: 'Đổi ngân sách', desc: 'Theo % hoặc số tiền', icon: Wallet },
]
const overnight = computed(() => isTime(f.value.window.on) && isTime(f.value.window.off) && f.value.window.off < f.value.window.on)
const modes = [{ value: 'percent', label: 'Theo %' }, { value: 'set', label: 'Đặt số tiền' }, { value: 'add', label: 'Cộng/trừ tiền' }]
const valueHint = computed(() => (f.value.mode === 'percent' ? 'Nhập số âm để giảm (vd -30).' : f.value.mode === 'add' ? 'Gõ dấu - ở đầu để trừ (vd -50.000).' : 'Đặt ngân sách ngày đúng bằng số này.'))
</script>

<template>
  <Modal :model-value="modelValue" fill width="1240px" :title="item && item.id ? 'Sửa lịch' : 'Thêm lịch'" subtitle="Tool sẽ chạy đúng giờ, kể cả khi bạn không mở trang này." @update:model-value="emit('update:modelValue', $event)">
    <div class="se">
      <div class="left">
        <div class="sum" aria-live="polite"><Sparkles :size="16" /><div><p>{{ summary }}</p><p v-if="f.action !== 'window'" class="sub">Giờ nào hôm nay chưa tới thì chạy luôn trong hôm nay.</p></div></div>

        <section class="sec">
          <h4><span class="no">1</span>Làm gì</h4>
          <div class="acts" role="radiogroup" aria-label="Hành động">
            <button v-for="a in actions" :key="a.value" type="button" role="radio" class="act" :class="{ on: f.action === a.value }" :aria-checked="f.action === a.value" @click="f.action = a.value">
              <span class="ic"><component :is="a.icon" :size="16" /></span><span class="at"><b>{{ a.label }}</b><small>{{ a.desc }}</small></span>
            </button>
          </div>
          <Field v-if="f.action === 'budget'" label="Đổi ngân sách" tip="scheduleBudget" :error="show('value')" :hint="valueHint" class="mt">
            <Segmented v-model="f.mode" :options="modes" size="sm" block />
            <input v-if="f.mode === 'percent'" v-model="f.value" type="number" step="any" class="input val" aria-label="Phần trăm" @input="touched.value = true" />
            <MoneyInput v-else v-model="f.value" class="val" :negative="f.mode === 'add'" :placeholder="f.mode === 'add' ? 'vd -50.000' : 'vd 500.000'" aria-label="Số tiền" @input="touched.value = true" />
          </Field>
        </section>

        <section class="sec">
          <h4><span class="no">2</span>Khi nào</h4>
          <Field v-if="f.action === 'window'" label="Khung giờ chạy" tip="scheduleWindow" :error="show('time')" :hint="overnight ? `Tắt sau nửa đêm: camp bật lúc ${f.window.on} và tắt lúc ${f.window.off} sáng hôm sau.` : 'Camp được bật lúc giờ bật và tắt lúc giờ tắt, cùng một danh sách. Ngày chạy tính theo ngày bật.'">
            <div class="win">
              <label><span>Bật lúc</span><input v-model="f.window.on" type="time" class="input" @input="touched.time = true" /></label>
              <span class="faint arrow">→</span>
              <label><span>Tắt lúc</span><input v-model="f.window.off" type="time" class="input" @input="touched.time = true" /></label>
            </div>
          </Field>
          <Field v-else label="Giờ chạy" tip="scheduleTime" :error="show('time')" hint="Thêm được nhiều giờ trong ngày (tối đa 24), mỗi giờ chạy 1 lần.">
            <div class="times">
              <span v-for="t in sortedTimes" :key="t" class="tchip num">{{ t }}<button type="button" :aria-label="'Bỏ giờ ' + t" @click="removeTime(t)"><X :size="13" /></button></span>
              <input v-if="adding" ref="timeEl" v-model="newTime" type="time" class="input tin" aria-label="Giờ cần thêm" @change="onTimeChange" @blur="onTimeBlur" @keydown.enter.prevent="onTimeChange" />
              <button v-else type="button" class="addt" @click="startAdd"><Plus :size="14" />Thêm giờ</button>
            </div>
          </Field>
          <Field label="Ngày chạy" :error="show('days')">
            <div class="days">
              <button v-for="d in DAY_ORDER" :key="d" type="button" class="day" :class="{ on: f.days.includes(d) }" :aria-pressed="f.days.includes(d)" @click="toggleDay(d)">{{ DAY_LABEL[d] }}</button>
            </div>
            <div class="quick">
              <button type="button" :class="{ on: preset === 'all' }" @click="setDays([0, 1, 2, 3, 4, 5, 6])">Hằng ngày</button>
              <button type="button" :class="{ on: preset === 'weekdays' }" @click="setDays([1, 2, 3, 4, 5])">T2–T6</button>
              <button type="button" :class="{ on: preset === 'weekend' }" @click="setDays([6, 0])">Cuối tuần</button>
            </div>
          </Field>
        </section>

        <section class="sec nmsec">
          <h4>Tên lịch <span class="opt">(không bắt buộc)</span></h4>
          <Field :error="show('name')" :hint="f.name.trim() ? '' : 'Để trống thì tool tự đặt tên như trên.'">
            <input v-model="f.name" class="input" maxlength="80" :placeholder="autoName" aria-label="Tên lịch" @input="touched.name = true" />
          </Field>
        </section>

        <div class="alerts">
          <Callout v-if="submitted && check.errors.conflict" tone="danger">{{ check.errors.conflict }}</Callout>
          <Callout v-for="w in check.warnings" :key="w" tone="warning">{{ w }}</Callout>
        </div>
      </div>

      <div class="right">
        <section class="sec">
          <h4><span class="no">3</span>Áp dụng cho</h4>
          <Segmented v-model="f.targetMode" :options="targetModes" block />
          <p class="hint2 md">{{ modeDesc }}</p>
          <FilterPicker v-model="f.targets" v-model:filter="f.filter" v-model:exclude="f.exclude" :auto="f.targetMode === 'filter'" keep-hidden :need-budget="f.action === 'budget'" :hide-budget="f.action !== 'budget'" :change="budgetPreview" :start-only-selected="startOnlySel" :tags-of="tagsOf" />
          <p v-if="targetErr" class="terr" role="alert"><CircleAlert :size="14" /><span>{{ targetErr }}</span></p>
        </section>
      </div>
    </div>

    <template #footer>
      <span class="fti"><b>{{ applied.count ? `${applied.count} ${applied.unit}${applied.auto ? ' đang khớp' : ' đã chọn'}` : applied.auto ? 'Chưa có mục nào khớp' : 'Chưa chọn mục nào' }}</b><small>{{ whenShort }}</small></span>
      <Btn class="cancel" @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :action="save">Lưu lịch</Btn>
    </template>
  </Modal>
</template>

<style scoped>
/* máy tính: 2 cột (trái: làm gì / khi nào; phải: danh sách), mỗi cột tự cuộn */
.se { flex: 1; min-height: 0; display: grid; grid-template-columns: 410px minmax(0, 1fr); border-top: 1px solid var(--border); }
.left { overflow: auto; padding: 18px 22px; background: var(--surface-2); border-right: 1px solid var(--border); }
.right { overflow: auto; padding: 18px 22px; min-width: 0; }
.right :deep(.tbl) { max-height: min(58vh, 560px); }
.sec { margin-bottom: 20px; }
.sec h4 { display: flex; align-items: center; gap: 9px; font-size: 15px; margin: 0 0 12px; letter-spacing: -.01em; }
.no { width: 22px; height: 22px; border-radius: 50%; background: var(--accent-grad); color: #fff; display: grid; place-items: center; font-size: 12px; font-weight: 700; flex: none; }
.sum { display: flex; gap: 10px; align-items: flex-start; margin-bottom: 18px; padding: 11px 14px; border-radius: var(--r-md); background: var(--accent-soft); font-size: 14px; line-height: 1.5; }
.sum svg { flex: none; margin-top: 3px; color: var(--accent); }
.sum p { margin: 0; } .sum p.sub { margin-top: 3px; font-size: 12.5px; color: var(--text-2); }
.acts { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.act { display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; border: 1.5px solid var(--border); border-radius: 13px; background: var(--surface); color: inherit; font: inherit; text-align: left; cursor: pointer; transition: border-color .15s, background .15s; }
.act:hover { border-color: var(--border-strong); }
.act .ic { width: 30px; height: 30px; border-radius: 9px; display: grid; place-items: center; background: var(--surface-3); color: var(--text-2); flex: none; transition: .15s; }
.at { min-width: 0; } .at b { display: block; font-size: 14px; font-weight: 650; line-height: 1.3; } .at small { display: block; font-size: 12px; color: var(--text-3); line-height: 1.35; margin-top: 1px; }
.act.on { border-color: var(--accent); background: color-mix(in srgb, var(--accent) 9%, var(--surface)); }
.act.on .ic { background: var(--accent); color: #fff; }
.mt { margin-top: 12px; margin-bottom: 0; }
.mt .val { margin-top: 8px; }
.times { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.tchip { display: inline-flex; align-items: center; gap: 4px; padding: 6px 6px 6px 12px; border-radius: 10px; background: var(--accent-soft); color: var(--accent); font-weight: 650; font-size: 14px; }
.tchip button { border: 0; background: none; color: inherit; display: grid; place-items: center; padding: 3px; border-radius: 6px; }
.tchip button:hover { background: var(--accent); color: #fff; }
.addt { display: inline-flex; align-items: center; gap: 5px; height: 34px; padding: 0 12px; border-radius: 10px; border: 1.5px dashed var(--border-strong); background: var(--surface); color: var(--accent); font: inherit; font-weight: 600; font-size: 13.5px; cursor: pointer; }
.addt:hover { border-color: var(--accent); }
.tin { width: 130px; padding: 6px 10px; }
.win { display: grid; grid-template-columns: 1fr auto 1fr; gap: 10px; align-items: end; }
.win label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-2); font-weight: 600; }
.arrow { padding-bottom: 10px; }
.days { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 6px; }
.day { height: 40px; border-radius: 11px; border: 1px solid var(--border-strong); background: var(--surface); font-weight: 650; font-size: 13.5px; color: var(--text-2); transition: .15s var(--ease); }
.day:hover { border-color: var(--accent); color: var(--accent); }
.day.on { background: var(--accent-grad); border-color: transparent; color: #fff; box-shadow: 0 4px 12px -4px var(--accent-ring); }
.quick { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; }
.quick button { border: 1px solid var(--border-strong); background: var(--surface); color: var(--text-2); padding: 4px 11px; border-radius: 99px; font-size: 12.5px; font-weight: 600; transition: .15s; }
.quick button:hover { border-color: var(--accent); color: var(--accent); }
.quick button.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.opt { font-size: 12.5px; font-weight: 500; color: var(--text-3); margin-left: 4px; }
.hint2 { font-size: 12.5px; color: var(--text-3); margin: 6px 0 0; line-height: 1.45; }
.hint2.md { margin: 6px 0 10px; }
.alerts:empty { display: none; }
.terr { display: flex; gap: 6px; align-items: flex-start; color: var(--danger); font-size: 13px; margin: 8px 0 0; }
.terr svg { flex: none; margin-top: 2px; }
.fti { margin-right: auto; display: flex; flex-direction: column; font-size: 12.5px; color: var(--text-2); line-height: 1.35; min-width: 0; }
.fti b { font-size: 14px; color: var(--text); }
.fti small { font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* máy tính bảng / cửa sổ hẹp */
@media (max-width: 1000px) { .se { grid-template-columns: 340px minmax(0, 1fr); } }
/* điện thoại: một cột cuộn chung, tên xuống cuối, nút Huỷ thay bằng dấu X */
@media (max-width: 760px) {
  .se { display: flex; flex-direction: column; overflow: auto; }
  .left, .right { display: contents; }
  .se > * > * { margin-left: 16px; margin-right: 16px; }
  .sum { margin-top: 14px; }
  .nmsec { order: 3; } .alerts { order: 2; }
  .right .sec { margin-bottom: 20px; }
  .right :deep(.tbl) { max-height: none; }
  .cancel { display: none; }
  .act { padding: 10px; }
  .days { gap: 5px; }
}
</style>
