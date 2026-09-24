<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Check } from 'lucide-vue-next'
import { api } from '../lib/api'
import { METRICS } from '../lib/constants'
import { validateRule } from '../lib/validate'
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

const blank = () => ({ name: '', metric: 'cpa', op: '>', value: 150000, minSpend: 100000, action: 'pause', pct: 20, maxBudget: '', minBudget: '', cooldownHours: 24, from: '', to: '', allActive: true, level: 'campaign', targets: [], enabled: true })
const f = ref(blank())
const scope = ref('all')
const submitted = ref(false)
const touched = reactive({})

watch(() => props.modelValue, (open) => {
  if (!open) return
  f.value = { ...blank(), ...JSON.parse(JSON.stringify(props.item || {})) }
  f.value.maxBudget = f.value.maxBudget || ''; f.value.minBudget = f.value.minBudget || ''
  scope.value = f.value.allActive ? 'all' : 'pick'
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
})

// Kiểm tra theo thời gian thực bằng đúng luật của server
const objs = computed(() => (state.objsLoaded && state.objs.length ? state.objs : null))
const check = computed(() => validateRule({ ...f.value, allActive: scope.value === 'all' }, { objs: objs.value, rules: state.rules }))
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')
const showTargets = computed(() => (submitted.value ? check.value.errors.targets : ''))
const touch = (k) => { touched[k] = true }
// các ô trần / sàn / % nằm chung một khối: gom lỗi lại
const adjErrors = computed(() => ['pct', 'maxBudget', 'minBudget'].map(show).filter(Boolean))

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
const actions = [{ value: 'pause', label: 'Tắt camp' }, { value: 'increase', label: 'Tăng ngân sách' }, { value: 'decrease', label: 'Giảm ngân sách' }]
const scopes = [{ value: 'all', label: 'Tất cả camp đang chạy' }, { value: 'pick', label: 'Chọn camp cụ thể' }]
</script>

<template>
  <Modal :model-value="modelValue" :title="item && item.id ? 'Sửa rule' : 'Thêm rule'" subtitle="Tool kiểm tra rule định kỳ dựa trên số liệu hôm nay." @update:model-value="emit('update:modelValue', $event)">
    <Field label="Tên rule" :error="show('name')"><input v-model="f.name" class="input" maxlength="120" placeholder="Vd: Tắt camp CPA cao" @input="touch('name')" /></Field>

    <Field label="Nếu (số liệu hôm nay)" :error="show('value')">
      <div class="inl">
        <select v-model="f.metric" class="input sel"><option v-for="(l, k) in METRICS" :key="k" :value="k">{{ l }}</option></select>
        <Segmented v-model="f.op" :options="ops" />
        <input v-model="f.value" type="number" step="any" min="0" class="input val" @input="touch('value')" />
      </div>
    </Field>

    <Field label="Chỉ xét khi đã chi tiêu tối thiểu" tip="minSpend" :error="show('minSpend')" hint="Tránh tắt nhầm khi camp mới chạy, chưa đủ dữ liệu."><input v-model="f.minSpend" type="number" min="0" class="input" style="max-width: 220px" @input="touch('minSpend')" /></Field>

    <Field label="Thì" tip="ruleAction">
      <Segmented v-model="f.action" :options="actions" block />
      <div v-if="f.action !== 'pause'" class="adj" :class="{ bad: adjErrors.length }">
        <label><span>Thay đổi</span><div class="with"><input v-model="f.pct" type="number" min="0" class="input" @input="touch('pct')" /><em>%</em></div></label>
        <label><span>Trần ngân sách</span><input v-model="f.maxBudget" type="number" min="0" class="input" placeholder="Không giới hạn" @input="touch('maxBudget')" /></label>
        <label><span>Sàn ngân sách</span><input v-model="f.minBudget" type="number" min="0" class="input" placeholder="Không giới hạn" @input="touch('minBudget')" /></label>
        <p v-for="m in adjErrors" :key="m" class="e">{{ m }}</p>
      </div>
    </Field>

    <div class="two">
      <Field label="Không lặp lại cho cùng camp trong (giờ)" tip="cooldown" :error="show('cooldownHours')"><input v-model="f.cooldownHours" type="number" min="0" class="input" @input="touch('cooldownHours')" /></Field>
      <Field label="Chỉ chạy trong khung giờ" tip="window" :error="show('window')"><div class="inl"><input v-model="f.from" type="time" class="input" @input="touch('window')" />→<input v-model="f.to" type="time" class="input" @input="touch('window')" /></div></Field>
    </div>

    <Field label="Áp dụng cho" :error="showTargets">
      <Segmented v-model="scope" :options="scopes" block />
      <div v-if="scope === 'pick'" style="margin-top: 12px"><TargetPicker v-model="f.targets" level="campaign" /></div>
    </Field>

    <Callout v-for="w in check.warnings" :key="w" tone="warning">{{ w }}</Callout>

    <template #footer>
      <Btn @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :action="save">Lưu rule</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.sel { width: 150px; } .val { width: 150px; }
.two { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.adj { display: grid; grid-template-columns: 110px 1fr 1fr; gap: 12px; margin-top: 12px; padding: 14px; border-radius: var(--r-md); background: var(--surface-2); }
.adj.bad { box-shadow: inset 0 0 0 1px var(--danger); }
.adj label { display: block; } .adj span { display: block; font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 5px; }
.adj .e { grid-column: 1 / -1; margin: 0; color: var(--danger); font-size: 13px; line-height: 1.45; }
.with { position: relative; } .with .input { padding-right: 30px; } .with em { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); font-style: normal; color: var(--text-3); }
@media (max-width: 620px) { .two, .adj { grid-template-columns: 1fr; } }
</style>
