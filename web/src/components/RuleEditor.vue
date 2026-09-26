<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Check, Eye, Plus, X, Sparkles } from 'lucide-vue-next'
import { api } from '../lib/api'
import { METRICS, RANGES } from '../lib/constants'
import { validateRule, MAX_CONDITIONS, TARGET_METRICS, COST_METRICS } from '../lib/validate'
import { describeRule } from '../lib/ruleText'
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
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
})

// Kiểm tra theo thời gian thực bằng đúng luật của server
const objs = computed(() => (state.objsLoaded && state.objs.length ? state.objs : null))
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
const check = computed(() => validateRule({ ...f.value, allActive: scope.value === 'all' }, { objs: objs.value, rules: state.rules, accountTargets: state.settings.accountTargets || {}, accounts: accounts.value.length ? accounts.value : null }))
// Câu tóm tắt: đọc thẳng từ form nên đổi ngay khi bạn sửa
const summary = computed(() => describeRule({ ...f.value, allActive: scope.value === 'all' }, { accounts: accounts.value }))
const isCost = (c) => COST_METRICS.includes(c.metric)
const resumeOn = computed({ get: () => f.value.resume === 'nextday', set: (v) => { f.value.resume = v ? 'nextday' : '' } })
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')

// ----- Điều kiện -----
const cErr = (i, field) => (submitted.value || touched[`c${i}`] ? check.value.errors[`c${i}.${field}`] : '')
const cErrors = (i) => ['metric', 'op', 'value'].map((k) => cErr(i, k)).filter(Boolean)
const condsError = computed(() => (submitted.value ? check.value.errors.conditions : ''))
const canTarget = (c) => TARGET_METRICS.includes(c.metric)
function addCond() {
  if (f.value.conditions.length < MAX_CONDITIONS) f.value.conditions.push({ metric: 'roas', op: '<', vs: '', value: 1.5 })
}
const removeCond = (i) => { if (f.value.conditions.length > 1) f.value.conditions.splice(i, 1) }
function onMetric(c) { if (!canTarget(c)) { c.vs = ''; delete c.factor } } // chỉ CPA/ROAS so được với mục tiêu
function onMode(c) { if (c.vs === 'target') { c.factor = c.factor || 100 } else { c.vs = ''; delete c.factor } }
const modesFor = (c) => [{ value: '', label: 'Số cụ thể' }, { value: 'target', label: c.metric === 'spend' ? '% CPA mục tiêu' : '% mục tiêu' }]
const budgetModes = [{ value: 'percent', label: 'Theo %' }, { value: 'amount', label: 'Theo số tiền' }]
const matchOptions = [{ value: 'all', label: 'Tất cả điều kiện đúng (VÀ)' }, { value: 'any', label: 'Một trong các điều kiện đúng (HOẶC)' }]
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
const actions = computed(() => [{ value: 'pause', label: `Tắt ${unit.value}` }, { value: 'increase', label: 'Tăng NS' }, { value: 'decrease', label: 'Giảm NS' }, { value: 'notify', label: 'Chỉ thông báo' }])
const scopes = computed(() => [{ value: 'all', label: `Tất cả ${unit.value} đang chạy` }, { value: 'pick', label: `Chọn ${unit.value} cụ thể` }])
</script>

<template>
  <Modal :model-value="modelValue" :title="item && item.id ? 'Sửa rule' : 'Thêm rule'" subtitle="Tool kiểm tra rule định kỳ theo khoảng thời gian bạn chọn." @update:model-value="emit('update:modelValue', $event)">
    <Field label="Tên rule" :error="show('name')"><input v-model="f.name" class="input" maxlength="120" placeholder="Vd: Tắt camp CPA cao" @input="touch('name')" /></Field>

    <div class="sum" aria-live="polite"><Sparkles :size="16" /><div><p v-for="(l, i) in summary" :key="i" :class="{ sub: i }">{{ l }}</p></div></div>

    <Field label="Số liệu tính trong khoảng" tip="range" :error="show('range')">
      <Segmented v-model="f.range" :options="RANGES" block />
    </Field>

    <Field label="Nếu" :error="condsError">
      <div class="conds">
        <div v-if="f.conditions.length > 1" class="mrow"><span>Rule chạy khi</span><Segmented v-model="f.match" :options="matchOptions" size="sm" /></div>
        <div v-for="(c, i) in f.conditions" :key="i" class="crow" :class="{ bad: cErrors(i).length }">
          <span v-if="i > 0" class="join">{{ f.match === 'any' ? 'HOẶC' : 'VÀ' }}</span>
          <div class="inl">
            <select v-model="c.metric" class="input sel" :aria-label="'Số liệu điều kiện ' + (i + 1)" @change="onMetric(c); touch('c' + i)"><option v-for="(l, k) in METRICS" :key="k" :value="k">{{ l }}</option></select>
            <Segmented v-model="c.op" :options="ops" size="sm" />
            <select v-if="canTarget(c)" v-model="c.vs" class="input vs" aria-label="So với" @change="onMode(c)"><option v-for="m in modesFor(c)" :key="m.value" :value="m.value">{{ m.label }}</option></select>
            <div v-if="c.vs === 'target'" class="with"><input v-model="c.factor" type="number" step="any" min="1" max="1000" class="input val" aria-label="Phần trăm so với mục tiêu" @input="touch('c' + i)" /><em>%</em></div>
            <MoneyInput v-else-if="isCost(c)" v-model="c.value" class="val" aria-label="Ngưỡng" placeholder="vd 150k" @input="touch('c' + i)" />
            <input v-else v-model="c.value" type="number" step="any" min="0" class="input val" aria-label="Ngưỡng" @input="touch('c' + i)" />
            <button v-if="f.conditions.length > 1" type="button" class="rm" :aria-label="'Bỏ điều kiện ' + (i + 1)" @click="removeCond(i)"><X :size="15" /></button>
          </div>
          <p v-if="c.vs === 'target'" class="th">Ngưỡng = <b>{{ c.metric === 'roas' ? 'ROAS' : 'CPA' }} mục tiêu</b> của từng tài khoản × {{ c.factor || 100 }}%<template v-if="c.metric === 'spend'"> (vd 200% = đã chi gấp đôi CPA mục tiêu; thêm điều kiện “Số kết quả nhỏ hơn 1” để cắt lỗ camp chưa ra đơn)</template>. Đặt mục tiêu ở <RouterLink to="/settings/targets">Cài đặt → Mục tiêu</RouterLink>.</p>
          <p v-for="m in cErrors(i)" :key="m" class="e">{{ m }}</p>
        </div>
        <Btn v-if="f.conditions.length < MAX_CONDITIONS" size="sm" :icon="Plus" class="addc" @click="addCond">Thêm điều kiện</Btn>
      </div>
    </Field>

    <Field label="Chỉ xét khi đã chi tiêu tối thiểu" tip="minSpend" :error="show('minSpend')" hint="Tránh tắt nhầm khi camp mới chạy, chưa đủ dữ liệu."><MoneyInput v-model="f.minSpend" style="max-width: 220px" placeholder="vd 300k" @input="touch('minSpend')" /></Field>

    <Field label="Thì" tip="ruleAction">
      <Segmented v-model="f.action" :options="actions" block />
      <div v-if="isBudget" class="adj" :class="{ bad: adjErrors.length }">
        <Segmented v-model="f.budgetMode" :options="budgetModes" size="sm" class="bm" />
        <label v-if="f.budgetMode === 'amount'"><span>Mỗi lần {{ f.action === 'increase' ? 'cộng' : 'trừ' }}</span><MoneyInput v-model="f.amount" placeholder="vd 200k" @input="touch('amount')" /></label>
        <label v-else><span>Thay đổi</span><div class="with"><input v-model="f.pct" type="number" min="0" class="input" @input="touch('pct')" /><em>%</em></div></label>
        <label><span>Trần ngân sách</span><MoneyInput v-model="f.maxBudget" placeholder="Không giới hạn" @input="touch('maxBudget')" /></label>
        <label><span>Sàn ngân sách</span><MoneyInput v-model="f.minBudget" placeholder="Không giới hạn" @input="touch('minBudget')" /></label>
        <p v-for="m in adjErrors" :key="m" class="e">{{ m }}</p>
      </div>
      <div v-if="f.action === 'pause'" class="resume" :class="{ bad: show('resumeAt') }">
        <label class="ck"><input v-model="resumeOn" type="checkbox" /> Tự bật lại vào ngày hôm sau lúc</label>
        <input v-model="f.resumeAt" type="time" class="input" :disabled="!resumeOn" aria-label="Giờ bật lại" @input="touch('resumeAt')" />
        <p class="th">Hợp để cắt lỗ theo ngày: {{ unit }} tốn quá nhiều hôm nay thì tắt, sáng mai chạy lại với số liệu mới. {{ unit === 'camp' ? 'Camp' : 'Nhóm QC' }} bạn đã tự bật lại hoặc đã hoàn tác thì tool để yên.</p>
        <p v-if="show('resumeAt')" class="e">{{ show('resumeAt') }}</p>
      </div>
    </Field>

    <div class="two">
      <Field :label="`Không lặp lại cho cùng ${unit} trong (giờ)`" tip="cooldown" :error="show('cooldownHours')"><input v-model="f.cooldownHours" type="number" min="0" class="input" @input="touch('cooldownHours')" /></Field>
      <Field label="Chỉ chạy trong khung giờ" tip="window" :error="show('window')"><div class="inl"><input v-model="f.from" type="time" class="input" @input="touch('window')" />→<input v-model="f.to" type="time" class="input" @input="touch('window')" /></div></Field>
    </div>

    <Field label="Áp dụng cho" :error="showTargets">
      <Segmented :model-value="f.level" :options="levels" block style="margin-bottom: 10px" @update:model-value="setLevel" />
      <Segmented v-model="scope" :options="scopes" block />
      <p v-if="isBudget" class="th">Ngân sách chỉ nằm ở một cấp: chiến dịch <b>CBO</b> giữ ngân sách ở chiến dịch, chiến dịch <b>ABO</b> giữ ở từng nhóm QC. {{ f.level === 'adset' ? 'Nhóm QC' : 'Chiến dịch' }} không có ngân sách riêng sẽ được bỏ qua.</p>
      <div v-if="scope === 'pick'" style="margin-top: 12px"><TargetPicker v-model="f.targets" :level="f.level" :need-budget="isBudget" /></div>
      <div v-else-if="multiAcc" class="accs">
        <span class="lb">Trong tài khoản</span>
        <button type="button" class="chip" :class="{ on: !f.accountIds.length }" @click="f.accountIds = []">Tất cả tài khoản</button>
        <button v-for="a in accounts" :key="a.id" type="button" class="chip" :class="{ on: f.accountIds.includes(a.id) }" :aria-pressed="f.accountIds.includes(a.id)" @click="toggleAcc(a.id)">{{ a.name }}</button>
      </div>
    </Field>

    <Callout v-for="w in check.warnings" :key="w" tone="warning">{{ w }}</Callout>

    <RulePreview v-if="pv || pvLoading || pvError" :data="pv" :rule="check.value" :loading="pvLoading" :error="pvError" />

    <template #footer>
      <Btn :icon="Eye" :loading="pvLoading" :action="preview">Xem trước</Btn>
      <Btn @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :action="save">Lưu rule</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.sel { width: 190px; } .val { width: 130px; } .vs { width: 130px; }
.conds { display: grid; gap: 10px; }
.mrow { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13.5px; color: var(--text-2); font-weight: 600; }
.crow { position: relative; padding: 12px 14px; border-radius: var(--r-md); background: var(--surface-2); }
.crow.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.join { display: inline-block; margin-bottom: 8px; padding: 1px 10px; border-radius: 99px; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.rm { display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 9px; background: transparent; color: var(--text-3); cursor: pointer; }
.rm:hover { background: var(--danger-soft); color: var(--danger); }
.crow .e { margin: 8px 0 0; color: var(--danger); font-size: 13px; line-height: 1.45; }
.th { margin: 8px 0 0; font-size: 13px; color: var(--text-3); line-height: 1.5; } .th b { color: var(--text-2); }
.addc { justify-self: start; }
.sum { display: flex; gap: 10px; align-items: flex-start; margin: -4px 0 18px; padding: 12px 14px; border-radius: var(--r-md); background: var(--accent-soft); color: var(--text); font-size: 14px; line-height: 1.5; }
.sum svg { flex: none; margin-top: 3px; color: var(--accent); }
.sum p { margin: 0; } .sum p.sub { margin-top: 4px; font-size: 13px; color: var(--text-2); }
.resume { display: grid; grid-template-columns: auto 130px; gap: 8px 12px; align-items: center; margin-top: 12px; padding: 12px 14px; border-radius: var(--r-md); background: var(--surface-2); }
.resume.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.resume .ck { display: flex; align-items: center; gap: 8px; font-size: 13.5px; font-weight: 600; color: var(--text-2); cursor: pointer; }
.resume .th, .resume .e { grid-column: 1 / -1; margin: 0; }
.resume .e { color: var(--danger); font-size: 13px; }
.accs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
.accs .lb { font-size: 13px; font-weight: 600; color: var(--text-2); margin-right: 2px; }
.chip { border: 1px solid var(--border-strong); background: var(--surface); color: var(--text-2); padding: 6px 13px; border-radius: 99px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: .15s; }
.chip:hover { border-color: var(--accent); color: var(--accent); }
.chip.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.two { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.adj .bm { grid-column: 1 / -1; justify-self: start; }
.adj { display: grid; grid-template-columns: 140px 1fr 1fr; gap: 12px; margin-top: 12px; padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
.adj.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.adj label { display: block; } .adj span { display: block; font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 5px; }
.adj .e { grid-column: 1 / -1; margin: 0; color: var(--danger); font-size: 13px; line-height: 1.45; }
.with { position: relative; } .with .input { padding-right: 30px; } .with em { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); font-style: normal; color: var(--text-3); }
@media (max-width: 620px) {
  .two, .adj { grid-template-columns: 1fr; }
  /* điều kiện: số liệu chiếm cả hàng, "so với" và ngưỡng chia đôi hàng dưới */
  .crow .inl { gap: 8px; }
  .crow .sel { flex: 1 1 100%; width: auto; }
  .crow .vs, .crow .val, .crow .with { flex: 1 1 120px; width: auto; }
  .crow .with .val { width: 100%; }
}
</style>
