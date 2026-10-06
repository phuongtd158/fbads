<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Check, Eye, Plus, X, Sparkles, CircleOff, TrendingUp, TrendingDown, Bell, ChevronDown, ChartNoAxesColumnIncreasing } from 'lucide-vue-next'
import { api } from '../lib/api'
import { METRICS, METRIC_SHORT, RANGES, RANGE_LABEL } from '../lib/constants'
import { validateRule, MAX_CONDITIONS, TARGET_METRICS, COST_METRICS, TOTAL_METRICS, MAX_TIERS, MAX_STEPS } from '../lib/validate'
import { describeRule } from '../lib/ruleText'
import { ruleName } from '../lib/names'
import { state } from '../stores/app'
import { toast } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'
import TargetPicker from './TargetPicker.vue'
import RulePreview from './RulePreview.vue'
import MoneyInput from './MoneyInput.vue'

const props = defineProps({ modelValue: Boolean, item: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])

const blank = () => ({ name: '', conditions: [{ metric: 'cpa', op: '>', value: 150000 }], match: 'all', range: 'last_3d', minSpend: 100000, action: 'pause', pct: 20, budgetMode: 'percent', amount: '', maxBudget: '', minBudget: '', cooldownHours: 24, resume: '', resumeAt: '06:00', from: '', to: '', allActive: true, accountIds: [], level: 'campaign', targets: [], enabled: true })
const f = ref(blank())
const scope = ref('all')
const submitted = ref(false)
const touched = reactive({})
const pv = ref(null)
const pvLoading = ref(false)
const pvError = ref('')

watch(() => props.modelValue, (open) => {
  if (!open) return
  pv.value = null; pvError.value = ''
  const item = JSON.parse(JSON.stringify(props.item || {}))
  f.value = { ...blank(), ...item }
  // rule cũ / mẫu cũ chỉ có metric/op/value ở ngoài cùng → thành 1 điều kiện
  // vs '' = "Số cụ thể" (ô chọn cần giá trị khớp một lựa chọn, không thì hiện trống)
  f.value.conditions = (item.conditions && item.conditions.length ? item.conditions : item.metric ? [{ metric: item.metric, op: item.op, value: item.value }] : blank().conditions).map((c) => ({ ...c, vs: c.vs || '' }))
  f.value.match = item.match === 'any' ? 'any' : 'all'
  f.value.accountIds = [...(item.accountIds || [])]
  f.value.maxBudget = f.value.maxBudget || ''; f.value.minBudget = f.value.minBudget || ''
  f.value.amount = f.value.amount || ''; f.value.budgetMode = f.value.budgetMode === 'amount' ? 'amount' : 'percent'; f.value.pct = f.value.pct || 20
  f.value.resumeAt = f.value.resumeAt || '06:00'
  scope.value = f.value.allActive ? 'all' : 'pick'
  advOpen.value = !!(f.value.from || f.value.to) // đã đặt khung giờ thì mở sẵn "Tuỳ chọn thêm"
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
})

// Kiểm tra theo thời gian thực bằng đúng luật của server
const objs = computed(() => (state.objsLoaded && state.objs.length ? state.objs : null))
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
// Để trống tên → tự đặt theo nội dung ("Tắt camp CPA > 150.000")
const autoName = computed(() => ruleName(f.value))
const check = computed(() => validateRule({ ...f.value, name: String(f.value.name || '').trim() || autoName.value, allActive: scope.value === 'all' }, { objs: objs.value, rules: state.rules, accountTargets: state.settings.accountTargets || {}, accounts: accounts.value.length ? accounts.value : null }))
// Câu tóm tắt: đọc thẳng từ form nên đổi ngay khi bạn sửa
const summary = computed(() => describeRule({ ...f.value, allActive: scope.value === 'all' }, { accounts: accounts.value }))
const isCost = (c) => COST_METRICS.includes(c.metric)
const resumeOn = computed({ get: () => f.value.resume === 'nextday', set: (v) => { f.value.resume = v ? 'nextday' : '' } })
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')

// ----- Điều kiện -----
const cErr = (i, field) => (submitted.value || touched[`c${i}`] ? check.value.errors[`c${i}.${field}`] : '')
const cErrors = (i) => ['metric', 'op', 'value', 'tiers'].map((k) => cErr(i, k)).filter(Boolean)
const condsError = computed(() => (submitted.value ? check.value.errors.conditions : ''))
const canTarget = (c) => TARGET_METRICS.includes(c.metric)
function addCond() {
  if (f.value.conditions.length < MAX_CONDITIONS) f.value.conditions.push({ metric: 'roas', op: '<', vs: '', value: 1.5 })
}
const removeCond = (i) => { if (f.value.conditions.length > 1) f.value.conditions.splice(i, 1) }
function onMetric(c) { if (c.vs === 'target' && !canTarget(c)) { c.vs = ''; delete c.factor } dropTiers(c) } // chỉ CPA/ROAS/chi tiêu so được với mục tiêu
// Nâng ngưỡng chi tiêu theo số kết quả: chỉ cho "Chi tiêu lớn hơn <số cụ thể>". Vd ngưỡng 150.000, từ 2 lead thì 200.000.
const canTier = (c) => c.metric === 'spend' && c.op === '>' && !c.vs
const tierMetrics = [{ value: 'leads', label: 'Lead' }, { value: 'results', label: 'Kết quả' }, { value: 'messages', label: 'Tin nhắn' }]
const tierUnit = (c) => (tierMetrics.find((m) => m.value === c.tierMetric) || tierMetrics[0]).label.toLowerCase()
function addTier(c) {
  const ts = c.tiers || (c.tiers = [])
  if (!c.tierMetric) c.tierMetric = 'leads'
  const last = ts[ts.length - 1] || { count: 0, value: Number(c.value) || 0 }
  if (ts.length < MAX_TIERS) ts.push({ count: last.count + (ts.length ? 1 : 2), value: (Number(last.value) || 0) + 50000 })
}
const removeTier = (c, j) => { c.tiers.splice(j, 1); if (!c.tiers.length) { delete c.tiers; delete c.tierMetric } }
function dropTiers(c) { if (!canTier(c)) { delete c.tiers; delete c.tierMetric } }
// Khoảng so sánh mặc định: khoảng dài hơn khoảng của rule (hôm nay → 7 ngày), khác khoảng của rule
const defaultCompare = () => (f.value.range === 'last_7d' ? 'last_3d' : 'last_7d')
function onMode(c) {
  if (c.vs === 'target') { c.factor = c.factor || 100; delete c.compareRange }
  else if (c.vs === 'range') { c.factor = c.factor || 130; c.compareRange = c.compareRange || defaultCompare() }
  else { c.vs = ''; delete c.factor; delete c.compareRange }
  dropTiers(c)
}
const modesFor = (c) => [
  { value: '', label: 'So với: số cụ thể' },
  ...(canTarget(c) ? [{ value: 'target', label: c.metric === 'spend' ? 'So với: % CPA mục tiêu' : 'So với: % mục tiêu' }] : []),
  { value: 'range', label: 'So với: % khoảng khác' },
]
const compareRanges = ['yesterday', 'last_3d', 'last_7d']
const budgetModes = [{ value: 'percent', label: 'Theo %' }, { value: 'amount', label: 'Theo số tiền' }]
const matchOptions = [{ value: 'all', label: 'Tất cả đều đúng (VÀ)' }, { value: 'any', label: 'Một cái đúng (HOẶC)' }]
const toggleAcc = (id) => { const s = new Set(f.value.accountIds); s.has(id) ? s.delete(id) : s.add(id); f.value.accountIds = [...s] }
const showTargets = computed(() => (submitted.value ? check.value.errors.targets : ''))
const touch = (k) => { touched[k] = true }
// các ô trần / sàn / % nằm chung một khối: gom lỗi lại
const adjErrors = computed(() => ['pct', 'amount', 'maxBudget', 'minBudget'].map(show).filter(Boolean))
// Sửa form thì kết quả xem trước cũ không còn đúng → xoá đi
watch(() => JSON.stringify(check.value.value), () => { pv.value = null; pvError.value = '' })

async function preview() {
  submitted.value = true
  if (!check.value.ok) { toast('Hãy sửa các mục báo lỗi trước khi xem trước', 'error'); return }
  pvLoading.value = true; pvError.value = ''; pv.value = null
  try { pv.value = await api('rules/preview', 'POST', check.value.value) } catch (e) { pvError.value = e.message } finally { pvLoading.value = false }
  await nextTick()
  const el = document.querySelector('.sheet .pv') // cuộn tới kết quả để người dùng thấy ngay
  if (el) el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
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
  await api('rules', 'POST', r.value)
  toast('Đã lưu rule')
  emit('saved'); emit('update:modelValue', false)
}
const ops = [{ value: '>', label: 'Lớn hơn' }, { value: '<', label: 'Nhỏ hơn' }]
// Cấp áp dụng: chiến dịch hoặc nhóm QC. Đổi cấp thì bỏ các mục đã chọn (thuộc cấp cũ).
const levels = [{ value: 'campaign', label: 'Chiến dịch' }, { value: 'adset', label: 'Nhóm QC' }]
const unit = computed(() => (f.value.level === 'adset' ? 'nhóm QC' : 'camp'))
function setLevel(v) { if (v !== f.value.level) { f.value.level = v; f.value.targets = [] } }
const isBudget = computed(() => f.value.action === 'increase' || f.value.action === 'decrease')
// Tăng theo bậc kết quả: không có điều kiện, số liệu luôn tính hôm nay; vẫn cần mục có ngân sách riêng
const isLadder = computed(() => f.value.action === 'ladder')
const needBudget = computed(() => isBudget.value || isLadder.value)
const ladderMetrics = [{ value: 'results', label: 'Số kết quả' }, { value: 'leads', label: 'Số lead' }, { value: 'messages', label: 'Số tin nhắn' }]
const stepUnit = computed(() => ({ results: 'kết quả', leads: 'lead', messages: 'tin nhắn' })[f.value.ladderMetric] || 'kết quả')
const stepModes = [{ value: 'percent', label: '%' }, { value: 'amount', label: 'đ' }]
const defaultSteps = () => [{ count: 1, mode: 'percent', value: 50 }, { count: 2, mode: 'percent', value: 50 }, { count: 3, mode: 'percent', value: 50, everyHours: 3 }]
watch(isLadder, (on) => {
  if (!on) return
  if (!(f.value.steps || []).length) f.value.steps = defaultSteps()
  if (!f.value.ladderMetric) f.value.ladderMetric = 'results'
  if (f.value.includeLearning === undefined) f.value.includeLearning = true
})
// Chỉ bậc cuối được lặp lại: thêm bậc thì chuyển "lặp lại" sang bậc mới
function addStep() {
  const st = f.value.steps
  if (st.length >= MAX_STEPS) return
  const last = st[st.length - 1] || { count: 0, mode: 'percent', value: 50 }
  const every = last.everyHours; delete last.everyHours
  st.push({ count: (Number(last.count) || 0) + 1, mode: last.mode, value: last.value, ...(every ? { everyHours: every } : {}) })
}
function removeStep(i) {
  const st = f.value.steps
  if (st.length <= 1) return
  const every = st[i].everyHours
  st.splice(i, 1)
  if (every && i === st.length) st[st.length - 1].everyHours = every
}
const repeatOn = computed({
  get: () => Number((f.value.steps || []).slice(-1)[0]?.everyHours) > 0,
  set: (v) => { const t = f.value.steps.slice(-1)[0]; if (v) t.everyHours = 3; else delete t.everyHours },
})
const sErr = (i) => (submitted.value || touched.steps ? check.value.errors[`s${i}`] : '')
const ladderErrors = computed(() => [show('ladderMetric'), submitted.value || touched.steps ? check.value.errors.steps : '', show('maxBudget')].filter(Boolean))
const actions = computed(() => [
  { value: 'pause', label: `Tắt ${unit.value}`, icon: CircleOff },
  { value: 'increase', label: 'Tăng ngân sách', icon: TrendingUp },
  { value: 'ladder', label: 'Tăng theo bậc kết quả', icon: ChartNoAxesColumnIncreasing },
  { value: 'decrease', label: 'Giảm ngân sách', icon: TrendingDown },
  { value: 'notify', label: 'Chỉ thông báo', icon: Bell },
])
const scopes = computed(() => [
  { value: 'all', label: `Tất cả ${unit.value} đang chạy`, desc: `Kể cả ${unit.value} mới tạo sau này` },
  { value: 'pick', label: `Chọn ${unit.value} cụ thể`, desc: 'Tích chọn từ danh sách' },
])
// "Tuỳ chọn thêm" (không lặp lại, khung giờ): thu gọn, hiện sẵn giá trị đang đặt; có lỗi thì tự mở
const advOpen = ref(false)
const advErr = computed(() => !!(show('cooldownHours') || show('window')))
watch(advErr, (v) => { if (v) advOpen.value = true })
const advSummary = computed(() => {
  const h = Number(f.value.cooldownHours)
  if (isLadder.value) return f.value.from && f.value.to ? `Chỉ chạy ${f.value.from}–${f.value.to}` : 'Chạy cả ngày'
  return [h > 0 ? `Không lặp lại ${h} giờ` : 'Có thể lặp lại mỗi lần kiểm tra', f.value.from && f.value.to ? `Chỉ chạy ${f.value.from}–${f.value.to}` : 'Chạy cả ngày'].join(' · ')
})
</script>

<template>
  <Modal :model-value="modelValue" fill width="800px" :title="item && item.id ? 'Sửa rule' : 'Thêm rule'" subtitle="Tool kiểm tra rule định kỳ theo khoảng thời gian bạn chọn." @update:model-value="emit('update:modelValue', $event)">
    <div class="re">
      <div class="sum" aria-live="polite"><Sparkles :size="16" /><div><p v-for="(l, i) in summary" :key="i" :class="{ sub: i }">{{ l }}</p></div></div>

      <section class="card2">
        <h4><span class="no">1</span>Khi nào</h4>
        <p v-if="isLadder" class="th lnote">Rule tăng theo bậc tính số kết quả trong <b>hôm nay</b> và không cần điều kiện: bậc nào đạt thì tăng theo bậc đó (đặt ở phần 2).</p>
        <Field v-if="!isLadder" label="Số liệu tính trong" tip="range" :error="show('range')">
          <Segmented v-model="f.range" :options="RANGES" block />
        </Field>
        <Field v-if="!isLadder" label="Nếu" :error="condsError">
          <div class="conds">
            <div v-if="f.conditions.length > 1" class="mrow"><span>Rule chạy khi</span><Segmented v-model="f.match" :options="matchOptions" size="sm" /></div>
            <div v-for="(c, i) in f.conditions" :key="i" class="crow" :class="{ bad: cErrors(i).length }">
              <span v-if="i > 0" class="join">{{ f.match === 'any' ? 'HOẶC' : 'VÀ' }}</span>
              <div class="cg" :class="{ one: f.conditions.length === 1 }">
                <select v-model="c.metric" class="input c-met" :aria-label="'Số liệu điều kiện ' + (i + 1)" @change="onMetric(c); touch('c' + i)"><option v-for="(l, k) in METRICS" :key="k" :value="k">{{ l }}</option></select>
                <select v-model="c.op" class="input c-op" aria-label="Lớn hơn hay nhỏ hơn" @change="dropTiers(c); touch('c' + i)"><option v-for="o in ops" :key="o.value" :value="o.value">{{ o.label }}</option></select>
                <div v-if="c.vs" class="with c-val"><input v-model="c.factor" type="number" step="any" min="1" max="1000" class="input" :aria-label="c.vs === 'range' ? 'Phần trăm so với khoảng khác' : 'Phần trăm so với mục tiêu'" @input="touch('c' + i)" /><em>%</em></div>
                <MoneyInput v-else-if="isCost(c)" v-model="c.value" class="c-val" aria-label="Ngưỡng" placeholder="vd 150.000" @input="touch('c' + i)" />
                <input v-else v-model="c.value" type="number" step="any" min="0" class="input c-val" aria-label="Ngưỡng" @input="touch('c' + i)" />
                <select v-model="c.vs" class="input c-vs" aria-label="So với" @change="onMode(c); touch('c' + i)"><option v-for="m in modesFor(c)" :key="m.value" :value="m.value">{{ m.label }}</option></select>
                <button v-if="f.conditions.length > 1" type="button" class="rm" :aria-label="'Bỏ điều kiện ' + (i + 1)" @click="removeCond(i)"><X :size="15" /></button>
                <select v-if="c.vs === 'range'" v-model="c.compareRange" class="input c-cmp" aria-label="Khoảng so sánh" @change="touch('c' + i)"><option v-for="r in compareRanges" :key="r" :value="r">của {{ RANGE_LABEL[r] }}</option></select>
              </div>
              <p v-if="c.vs === 'target'" class="th">Ngưỡng = <b>{{ c.metric === 'roas' ? 'ROAS' : 'CPA' }} mục tiêu</b> của từng tài khoản × {{ c.factor || 100 }}%<template v-if="c.metric === 'spend'"> (vd 200% = đã chi gấp đôi CPA mục tiêu; thêm điều kiện “Số kết quả nhỏ hơn 1” để cắt lỗ camp chưa ra đơn)</template>. Đặt mục tiêu ở <RouterLink to="/settings/targets">Cài đặt → Mục tiêu</RouterLink>.</p>
              <p v-if="c.vs === 'range'" class="th">Ngưỡng = <b>{{ METRIC_SHORT[c.metric] }} của chính {{ unit }} đó</b> trong khoảng so sánh × {{ c.factor || 100 }}%<template v-if="TOTAL_METRICS.includes(c.metric)"> (tính trung bình mỗi ngày, để so được hai khoảng dài ngắn khác nhau)</template>. Vd “CPA hôm nay lớn hơn 130% của 7 ngày gần nhất” = CPA hôm nay cao hơn 30% so với bình thường. {{ unit === 'camp' ? 'Camp' : 'Nhóm QC' }} chưa chi tiêu trong khoảng so sánh thì được bỏ qua.</p>
              <div v-if="canTier(c) && (c.tiers || []).length" class="tiers">
                <div class="thd"><span>Nâng ngưỡng khi đã có</span><select v-model="c.tierMetric" class="input" aria-label="Loại kết quả để nâng ngưỡng" @change="touch('c' + i)"><option v-for="m in tierMetrics" :key="m.value" :value="m.value">{{ m.label }}</option></select></div>
                <div v-for="(t, j) in c.tiers" :key="j" class="trow">
                  <span>Từ</span><input v-model.number="t.count" type="number" min="1" step="1" class="input tc" :aria-label="'Số ' + tierUnit(c) + ' của bậc ' + (j + 1)" @input="touch('c' + i)" />
                  <span>{{ tierUnit(c) }} thì ngưỡng là</span><MoneyInput v-model="t.value" class="tv" :aria-label="'Ngưỡng chi tiêu của bậc ' + (j + 1)" placeholder="vd 200.000" @input="touch('c' + i)" />
                  <button type="button" class="rm" :aria-label="'Bỏ bậc ' + (j + 1)" @click="removeTier(c, j)"><X :size="15" /></button>
                </div>
                <button v-if="c.tiers.length < MAX_TIERS" type="button" class="addc" @click="addTier(c)"><Plus :size="14" />Thêm bậc</button>
                <p class="th">Chưa đạt bậc nào thì dùng ngưỡng {{ Number(c.value || 0).toLocaleString('vi-VN') }}. Đạt nhiều bậc thì dùng bậc cao nhất. Số {{ tierUnit(c) }} tính trong cùng khoảng của rule.</p>
              </div>
              <button v-else-if="canTier(c)" type="button" class="addc tadd" @click="addTier(c); touch('c' + i)"><Plus :size="14" />Nâng ngưỡng khi có kết quả</button>
              <p v-for="m in cErrors(i)" :key="m" class="e">{{ m }}</p>
            </div>
            <button v-if="f.conditions.length < MAX_CONDITIONS" type="button" class="addc" @click="addCond"><Plus :size="14" />Thêm điều kiện</button>
          </div>
        </Field>
        <Field tip="minSpend" :error="show('minSpend')" :hint="isLadder ? 'Vd 100.000: chỉ tăng khi đã chi từ 100.000 hôm nay.' : 'Tránh tắt nhầm khi camp mới chạy, chưa đủ dữ liệu.'" class="last">
          <div class="msp"><span>Chỉ xét khi đã chi tối thiểu</span><MoneyInput v-model="f.minSpend" class="msi" placeholder="vd 300.000" aria-label="Chi tiêu tối thiểu" @input="touch('minSpend')" /></div>
        </Field>
      </section>

      <section class="card2">
        <h4><span class="no">2</span>Thì làm gì</h4>
        <div class="acts" role="radiogroup" aria-label="Hành động">
          <button v-for="a in actions" :key="a.value" type="button" role="radio" class="act" :class="{ on: f.action === a.value }" :aria-checked="f.action === a.value" @click="f.action = a.value">
            <span class="ic"><component :is="a.icon" :size="16" /></span><b>{{ a.label }}</b>
          </button>
        </div>
        <div v-if="isBudget" class="adj" :class="{ bad: adjErrors.length }">
          <Segmented v-model="f.budgetMode" :options="budgetModes" size="sm" class="bm" />
          <label v-if="f.budgetMode === 'amount'"><span>Mỗi lần {{ f.action === 'increase' ? 'cộng' : 'trừ' }}</span><MoneyInput v-model="f.amount" placeholder="vd 200.000" @input="touch('amount')" /></label>
          <label v-else><span>Thay đổi</span><div class="with"><input v-model="f.pct" type="number" min="0" class="input" @input="touch('pct')" /><em>%</em></div></label>
          <label><span>Trần ngân sách</span><MoneyInput v-model="f.maxBudget" placeholder="Không giới hạn" @input="touch('maxBudget')" /></label>
          <label><span>Sàn ngân sách</span><MoneyInput v-model="f.minBudget" placeholder="Không giới hạn" @input="touch('minBudget')" /></label>
          <p v-for="m in adjErrors" :key="m" class="e">{{ m }}</p>
        </div>
        <div v-if="isLadder" class="ladder" :class="{ bad: ladderErrors.length }">
          <div class="lhd"><span>Đếm theo</span><select v-model="f.ladderMetric" class="input" aria-label="Loại kết quả để tính bậc" @change="touch('steps')"><option v-for="m in ladderMetrics" :key="m.value" :value="m.value">{{ m.label }}</option></select></div>
          <div v-for="(t, i) in f.steps" :key="i" class="lrow" :class="{ bad: sErr(i) }">
            <b class="lno">Bậc {{ i + 1 }}</b>
            <span>Khi có từ</span><input v-model.number="t.count" type="number" min="1" step="1" class="input lc" :aria-label="'Số ' + stepUnit + ' của bậc ' + (i + 1)" @input="touch('steps')" />
            <span>{{ stepUnit }} thì tăng</span>
            <MoneyInput v-if="t.mode === 'amount'" v-model="t.value" class="lv" :aria-label="'Số tiền tăng ở bậc ' + (i + 1)" placeholder="vd 50.000" @input="touch('steps')" />
            <input v-else v-model.number="t.value" type="number" min="1" max="100" class="input lv" :aria-label="'Phần trăm tăng ở bậc ' + (i + 1)" @input="touch('steps')" />
            <Segmented v-model="t.mode" :options="stepModes" size="sm" />
            <button v-if="f.steps.length > 1" type="button" class="rm" :aria-label="'Bỏ bậc ' + (i + 1)" @click="removeStep(i)"><X :size="15" /></button>
            <label v-if="i === f.steps.length - 1" class="ck lrep"><input v-model="repeatOn" type="checkbox" /> Sau đó cứ mỗi <input v-model.number="t.everyHours" type="number" min="1" max="24" class="input lh" :disabled="!repeatOn" aria-label="Lặp lại mỗi mấy giờ" @input="touch('steps')" /> giờ tăng lại</label>
          </div>
          <button v-if="f.steps.length < MAX_STEPS" type="button" class="addc" @click="addStep"><Plus :size="14" />Thêm bậc</button>
          <div class="lcap"><span>Trần ngân sách</span><MoneyInput v-model="f.maxBudget" class="msi" placeholder="Bắt buộc, vd 2.000.000" aria-label="Trần ngân sách" @input="touch('maxBudget')" /></div>
          <label class="ck"><input v-model="f.includeLearning" type="checkbox" /> Áp dụng cả {{ unit }} đang trong giai đoạn học</label>
          <p class="th">Mỗi bậc chạy 1 lần mỗi ngày, sang ngày mới tính lại từ đầu. Nhảy nhiều bậc cùng lúc thì chỉ tăng theo bậc cao nhất. Rule này không bị giới hạn % thay đổi mỗi ngày ở Cài đặt → Bảo vệ, nên bắt buộc có trần.</p>
          <p v-for="m in ladderErrors" :key="m" class="e">{{ m }}</p>
        </div>
        <div v-if="f.action === 'pause'" class="resume" :class="{ bad: show('resumeAt') }">
          <label class="ck"><input v-model="resumeOn" type="checkbox" /> Tự bật lại sáng hôm sau lúc</label>
          <input v-model="f.resumeAt" type="time" class="input" :disabled="!resumeOn" aria-label="Giờ bật lại" @input="touch('resumeAt')" />
          <p class="th">Hợp để cắt lỗ theo ngày: {{ unit }} tốn quá nhiều hôm nay thì tắt, sáng mai chạy lại với số liệu mới. {{ unit === 'camp' ? 'Camp' : 'Nhóm QC' }} bạn đã tự bật lại hoặc đã hoàn tác thì tool để yên.</p>
          <p v-if="show('resumeAt')" class="e">{{ show('resumeAt') }}</p>
        </div>
      </section>

      <section class="card2">
        <h4><span class="no">3</span>Áp dụng cho</h4>
        <Field :error="showTargets" class="last">
          <Segmented :model-value="f.level" :options="levels" block class="lvl" @update:model-value="setLevel" />
          <div class="modes" role="radiogroup" aria-label="Phạm vi áp dụng">
            <button v-for="m in scopes" :key="m.value" type="button" role="radio" class="mode" :class="{ on: scope === m.value }" :aria-checked="scope === m.value" @click="scope = m.value">
              <span class="rd" /><span class="mt"><b>{{ m.label }}</b><small>{{ m.desc }}</small></span>
            </button>
          </div>
          <p v-if="needBudget" class="th">Ngân sách chỉ nằm ở một cấp: chiến dịch <b>CBO</b> giữ ngân sách ở chiến dịch, chiến dịch <b>ABO</b> giữ ở từng nhóm QC. {{ f.level === 'adset' ? 'Nhóm QC' : 'Chiến dịch' }} không có ngân sách riêng sẽ được bỏ qua.</p>
          <div v-if="scope === 'pick'" style="margin-top: 12px"><TargetPicker v-model="f.targets" :level="f.level" :need-budget="needBudget" /></div>
          <div v-else-if="multiAcc" class="accs">
            <span class="lb">Trong tài khoản</span>
            <button type="button" class="chip" :class="{ on: !f.accountIds.length }" @click="f.accountIds = []">Tất cả</button>
            <button v-for="a in accounts" :key="a.id" type="button" class="chip" :class="{ on: f.accountIds.includes(a.id) }" :aria-pressed="f.accountIds.includes(a.id)" @click="toggleAcc(a.id)">{{ a.name }}</button>
          </div>
        </Field>
      </section>

      <section class="adv" :class="{ open: advOpen, bad: advErr }">
        <button type="button" class="advh" :aria-expanded="advOpen" @click="advOpen = !advOpen"><b>Tuỳ chọn thêm</b><small>{{ advSummary }}</small><ChevronDown :size="17" class="chev" /></button>
        <div v-if="advOpen" class="advb">
          <Field v-if="!isLadder" :label="`Không lặp lại cho cùng ${unit} trong`" tip="cooldown" :error="show('cooldownHours')"><div class="with"><input v-model="f.cooldownHours" type="number" min="0" class="input" @input="touch('cooldownHours')" /><em>giờ</em></div></Field>
          <Field label="Chỉ chạy trong khung giờ" tip="window" :error="show('window')" hint="Để trống là chạy cả ngày.">
            <div class="tw"><input v-model="f.from" type="time" class="input" aria-label="Từ giờ" @input="touch('window')" /><span class="faint">→</span><input v-model="f.to" type="time" class="input" aria-label="Đến giờ" @input="touch('window')" /></div>
          </Field>
        </div>
      </section>

      <section class="nmsec">
        <h4>Tên rule <span class="opt">(không bắt buộc)</span></h4>
        <Field :error="show('name')" :hint="f.name.trim() ? '' : 'Để trống thì tool tự đặt tên như trên.'" class="last">
          <input v-model="f.name" class="input" maxlength="80" :placeholder="autoName" aria-label="Tên rule" @input="touch('name')" />
        </Field>
      </section>

      <Callout v-for="w in check.warnings" :key="w" tone="warning">{{ w }}</Callout>

      <RulePreview v-if="pv || pvLoading || pvError" :data="pv" :rule="check.value" :loading="pvLoading" :error="pvError" />
    </div>

    <template #footer>
      <Btn :icon="Eye" :loading="pvLoading" :action="preview" class="pvb">Xem trước</Btn>
      <Btn class="cancel" @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :action="save">Lưu rule</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.re { flex: 1; min-height: 0; overflow: auto; padding: 0 26px 18px; border-top: 1px solid var(--border); }
/* tóm tắt dính ở đầu khi cuộn */
.sum { position: sticky; top: 0; z-index: 3; display: flex; gap: 10px; align-items: flex-start; margin: 0 -26px 14px; padding: 12px 26px; background: linear-gradient(var(--accent-soft), var(--accent-soft)), var(--surface); border-bottom: 1px solid var(--border); font-size: 14px; line-height: 1.5; }
.sum svg { flex: none; margin-top: 3px; color: var(--accent); }
.sum p { margin: 0; } .sum p.sub { margin-top: 3px; font-size: 13px; color: var(--text-2); }
.card2 { border: 1px solid var(--border); border-radius: 16px; padding: 14px 16px; margin-bottom: 14px; }
.card2 h4, .nmsec h4 { display: flex; align-items: center; gap: 9px; font-size: 15px; margin: 0 0 12px; letter-spacing: -.01em; }
.no { width: 22px; height: 22px; border-radius: 50%; background: var(--accent-grad); color: #fff; display: grid; place-items: center; font-size: 12px; font-weight: 700; flex: none; }
.last { margin-bottom: 0; }
.conds { display: grid; grid-template-columns: minmax(0, 1fr); gap: 10px; }
.mrow { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13.5px; color: var(--text-2); font-weight: 600; }
.crow { position: relative; padding: 10px; border-radius: var(--r-md); background: var(--surface-2); }
.crow.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.join { display: inline-block; margin-bottom: 8px; padding: 1px 10px; border-radius: 99px; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
/* một điều kiện = một hàng: số liệu · lớn/nhỏ hơn · ngưỡng · so với · bỏ */
.cg { display: grid; grid-template-columns: minmax(0, 1.25fr) 118px minmax(0, 1fr) minmax(0, 1.2fr) 32px; gap: 8px; align-items: center; }
.cg .input { padding-top: 8px; padding-bottom: 8px; font-size: 14px; }
.c-cmp { grid-column: 3 / 5; }
.rm { display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 9px; background: transparent; color: var(--text-3); cursor: pointer; }
.rm:hover { background: var(--danger-soft); color: var(--danger); }
.crow .e { margin: 8px 0 0; color: var(--danger); font-size: 13px; line-height: 1.45; }
.th { margin: 8px 0 0; font-size: 13px; color: var(--text-3); line-height: 1.5; } .th b { color: var(--text-2); }
.addc { justify-self: start; display: inline-flex; align-items: center; gap: 5px; border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; font-size: 13.5px; padding: 2px; cursor: pointer; }
.tadd { margin-top: 8px; font-size: 13px; }
.tiers { display: grid; gap: 8px; margin-top: 10px; padding: 10px 12px; border-radius: var(--r-md); border: 1px dashed var(--border-strong); background: var(--surface); }
.thd, .trow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13.5px; color: var(--text-2); }
.thd { font-weight: 600; }
.tiers .input { padding-top: 7px; padding-bottom: 7px; font-size: 14px; width: auto; }
.tiers .tc { width: 72px; }
.tiers .tv { width: 150px; }
.tiers .th { margin: 0; }
.msp { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 14px; color: var(--text-2); }
.msi { width: 170px; }
.acts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.lnote { margin: 0 0 12px; }
.ladder { display: grid; gap: 10px; margin-top: 12px; padding: 12px 14px; border-radius: var(--r-md); background: var(--surface-2); }
.ladder.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.lhd, .lrow, .lcap { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13.5px; color: var(--text-2); }
.lhd { font-weight: 600; }
.lrow { padding: 8px 10px; border-radius: 11px; background: var(--surface); }
.lrow.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.lno { color: var(--text); font-size: 13px; min-width: 46px; }
.ladder .input { padding-top: 7px; padding-bottom: 7px; font-size: 14px; width: auto; }
.ladder .lc { width: 64px; } .ladder .lv { width: 110px; } .ladder .lh { width: 58px; margin: 0 4px; }
.lrep { flex-basis: 100%; padding-left: 54px; }
.ladder .ck { display: flex; align-items: center; gap: 6px; font-size: 13.5px; font-weight: 600; color: var(--text-2); cursor: pointer; }
.ladder .th, .ladder .e { margin: 0; } .ladder .e { color: var(--danger); font-size: 13px; }
.act { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 11px 8px; border: 1.5px solid var(--border); border-radius: 13px; background: var(--surface); color: inherit; font: inherit; text-align: center; cursor: pointer; transition: border-color .15s, background .15s; }
.act:hover { border-color: var(--border-strong); }
.act .ic { width: 30px; height: 30px; border-radius: 9px; display: grid; place-items: center; background: var(--surface-3); color: var(--text-2); flex: none; transition: .15s; }
.act b { font-size: 14px; font-weight: 650; line-height: 1.3; }
.act.on { border-color: var(--accent); background: var(--accent-soft); }
.act.on .ic { background: var(--accent); color: #fff; }
.resume { display: grid; grid-template-columns: auto 136px; justify-content: start; gap: 8px 12px; align-items: center; margin-top: 12px; padding: 12px 14px; border-radius: var(--r-md); background: var(--surface-2); }
.resume.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.resume .ck { display: flex; align-items: center; gap: 8px; font-size: 13.5px; font-weight: 600; color: var(--text-2); cursor: pointer; }
.resume .input { padding: 7px 10px; }
.resume .th, .resume .e { grid-column: 1 / -1; margin: 0; }
.resume .e { color: var(--danger); font-size: 13px; }
.lvl { margin-bottom: 10px; }
.modes { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.mode { display: flex; gap: 10px; align-items: flex-start; text-align: left; padding: 10px 12px; border: 1.5px solid var(--border); border-radius: 13px; background: var(--surface); color: inherit; font: inherit; cursor: pointer; transition: border-color .15s, background .15s; }
.mode:hover { border-color: var(--border-strong); }
.mode.on { border-color: var(--accent); background: var(--accent-soft); }
.rd { width: 18px; height: 18px; border-radius: 50%; border: 2px solid var(--border-strong); flex: none; margin-top: 2px; display: grid; place-items: center; }
.mode.on .rd { border-color: var(--accent); }
.mode.on .rd::after { content: ''; width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
.mt { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.mt b { font-size: 14px; font-weight: 650; } .mt small { font-size: 12px; color: var(--text-3); line-height: 1.4; }
.accs { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 12px; }
.accs .lb { font-size: 13px; font-weight: 600; color: var(--text-2); margin-right: 2px; }
.chip { border: 1px solid var(--border-strong); background: var(--surface); color: var(--text-2); padding: 5px 12px; border-radius: 99px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: .15s; }
.chip:hover { border-color: var(--accent); color: var(--accent); }
.chip.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.adv { border: 1px solid var(--border); border-radius: 16px; margin-bottom: 14px; }
.adv.bad { border-color: var(--danger); }
.advh { width: 100%; display: flex; align-items: center; gap: 10px; padding: 13px 16px; border: 0; background: none; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.advh b { font-size: 14.5px; font-weight: 650; white-space: nowrap; }
.advh small { font-size: 12.5px; color: var(--text-3); min-width: 0; }
.chev { margin-left: auto; flex: none; color: var(--text-3); transition: transform .2s; }
.adv.open .chev { transform: rotate(180deg); }
.advb { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; padding: 0 16px 2px; }
.tw { display: flex; align-items: center; gap: 8px; } .tw .input { flex: 1; min-width: 0; }
.nmsec { margin: 4px 0 14px; }
.opt { font-size: 12.5px; font-weight: 500; color: var(--text-3); }
.adj .bm { grid-column: 1 / -1; justify-self: start; }
.adj { display: grid; grid-template-columns: 140px 1fr 1fr; gap: 12px; margin-top: 12px; padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
.adj.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.adj label { display: block; } .adj span { display: block; font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 5px; }
.adj .e { grid-column: 1 / -1; margin: 0; color: var(--danger); font-size: 13px; line-height: 1.45; }
.with { position: relative; } .with .input { padding-right: 38px; } .with em { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); font-style: normal; color: var(--text-3); font-size: 13px; }
.pvb { margin-right: auto; }
/* điện thoại: toàn màn hình, điều kiện 3 hàng, hành động 2 x 2, nút Huỷ thay bằng dấu X */
@media (max-width: 640px) {
  .re { padding: 0 16px 16px; }
  .sum { margin: 0 -16px 12px; padding: 10px 16px; }
  .sum p.sub { display: none; }
  .card2 { padding: 12px; }
  .cg { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 32px; }
  .c-met { grid-column: 1 / 3; } .cg.one .c-met { grid-column: 1 / -1; }
  .rm { grid-column: 3; grid-row: 1; }
  .c-op { grid-column: 1; } .c-val { grid-column: 2 / -1; }
  .c-vs, .c-cmp { grid-column: 1 / -1; }
  .acts { grid-template-columns: 1fr 1fr; } .act:last-child { grid-column: 1 / -1; }
  .lrep { padding-left: 0; }
  .mrow :deep(.seg) { display: flex; width: 100%; } .mrow :deep(.seg button) { flex: 1; padding-left: 6px; padding-right: 6px; }
  .resume { grid-template-columns: 1fr 120px; }
  .act { flex-direction: row; justify-content: flex-start; text-align: left; padding: 10px; }
  .modes, .advb, .adj { grid-template-columns: 1fr; }
  .advh { flex-wrap: wrap; row-gap: 2px; } .advh small { order: 3; flex-basis: 100%; }
  .cancel { display: none; }
}
</style>
