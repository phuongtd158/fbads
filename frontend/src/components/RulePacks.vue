<script setup>
// Bộ rule theo mục tiêu chiến dịch: nhập chi phí mục tiêu một lần → tạo sẵn cả bộ cắt lỗ, tắt camp đắt, tăng ngân sách, chống cháy quảng cáo.
import { ref, computed, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import { api } from '../lib/api'
import { fmt } from '../lib/format'
import { validateRule } from '../lib/validate'
import { describeRule } from '../lib/ruleText'
import { toast, toastError } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Segmented from './Segmented.vue'
import MoneyInput from './MoneyInput.vue'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'saved'])

const GOALS = {
  sales: { label: 'Bán hàng', cost: 'cpa', count: 'results', costName: 'CPA mục tiêu (chi phí mỗi đơn)', unit: 'đơn', def: 150000 },
  messages: { label: 'Tin nhắn', cost: 'costPerMessage', count: 'messages', costName: 'Chi phí mỗi tin nhắn mục tiêu', unit: 'tin nhắn', def: 50000 },
  leads: { label: 'Lead', cost: 'costPerLead', count: 'leads', costName: 'Chi phí mỗi lead mục tiêu', unit: 'lead', def: 80000 },
}
const goals = Object.entries(GOALS).map(([value, g]) => ({ value, label: g.label }))
const goal = ref('sales')
const target = ref(GOALS.sales.def)
const cap = ref(2000000)
const picked = ref({ stop: true, costly: true, scale: true, fatigue: true })
const busy = ref(false)
watch(goal, (g) => { target.value = GOALS[g].def })
watch(() => props.modelValue, (o) => { if (o) { goal.value = 'sales'; target.value = GOALS.sales.def; cap.value = 2000000; picked.value = { stop: true, costly: true, scale: true, fatigue: true } } })

// Ngưỡng làm tròn cho dễ đọc
const round = (n) => Math.max(1000, Math.round(n / 1000) * 1000)
const rules = computed(() => {
  const g = GOALS[goal.value], T = Number(target.value) || 0
  const base = { match: 'all', allActive: true, level: 'campaign', accountIds: [], targets: [], enabled: true, from: '', to: '' }
  return [
    { key: 'stop', title: 'Cắt lỗ trong ngày', why: `Đã chi gấp đôi mục tiêu mà chưa ra ${g.unit} nào thì tắt, sáng mai tự chạy lại.`,
      r: { ...base, name: `[${g.label}] Cắt lỗ trong ngày`, conditions: [{ metric: 'spend', op: '>', value: round(2 * T) }, { metric: g.count, op: '<', value: 1 }], range: 'today', minSpend: round(T), action: 'pause', cooldownHours: 24, resume: 'nextday', resumeAt: '06:00' } },
    { key: 'costly', title: 'Tắt camp đắt', why: `Chi phí mỗi ${g.unit} 3 ngày vượt 150% mục tiêu thì tắt.`,
      r: { ...base, name: `[${g.label}] Tắt camp đắt`, conditions: [{ metric: g.cost, op: '>', value: round(1.5 * T) }], range: 'last_3d', minSpend: round(3 * T), action: 'pause', cooldownHours: 24 } },
    { key: 'scale', title: 'Tăng ngân sách camp tốt', why: `Chi phí mỗi ${g.unit} 3 ngày dưới 80% mục tiêu và có từ 3 ${g.unit} thì tăng 20%, tối đa ${fmt(cap.value || 0)}.`,
      r: { ...base, name: `[${g.label}] Tăng ngân sách camp tốt`, conditions: [{ metric: g.cost, op: '<', value: round(0.8 * T) }, { metric: g.count, op: '>', value: 2 }], range: 'last_3d', minSpend: round(2 * T), action: 'increase', pct: 20, budgetMode: 'percent', maxBudget: Number(cap.value) || 0, cooldownHours: 24 } },
    { key: 'fatigue', title: 'Cảnh báo cháy quảng cáo', why: 'Tần suất 7 ngày trên 3,5 và CTR dưới 1%: khách đã xem quá nhiều, nên thay nội dung.',
      r: { ...base, name: `[${g.label}] Cảnh báo cháy quảng cáo`, conditions: [{ metric: 'frequency', op: '>', value: 3.5 }, { metric: 'ctr', op: '<', value: 1 }], range: 'last_7d', minSpend: round(T), action: 'notify', cooldownHours: 24 } },
  ]
})
const checks = computed(() => rules.value.map((x) => validateRule(x.r)))
const firstError = computed(() => {
  if (!(Number(target.value) > 0)) return 'Nhập chi phí mục tiêu lớn hơn 0'
  if (picked.value.scale && !(Number(cap.value) > 0)) return 'Nhập trần ngân sách cho rule tăng ngân sách'
  const i = rules.value.findIndex((x, k) => picked.value[x.key] && !checks.value[k].ok)
  return i >= 0 ? `${rules.value[i].title}: ${checks.value[i].first}` : ''
})
const count = computed(() => rules.value.filter((x) => picked.value[x.key]).length)

async function create() {
  if (firstError.value) { toast(firstError.value, 'error'); return }
  busy.value = true
  let n = 0
  try {
    for (const [k, x] of rules.value.entries()) if (picked.value[x.key]) { await api('rules', 'POST', checks.value[k].value); n++ }
    toast(`Đã tạo ${n} rule`)
    emit('saved'); emit('update:modelValue', false)
  } catch (e) { toastError(e); if (n) emit('saved') } finally { busy.value = false }
}
</script>

<template>
  <Modal :model-value="modelValue" title="Bộ rule theo mục tiêu" subtitle="Nhập chi phí mục tiêu một lần, tool tạo sẵn các rule hay dùng. Sửa từng rule sau cũng được." width="640px" @update:model-value="emit('update:modelValue', $event)">
    <Field label="Chiến dịch của bạn chạy để"><Segmented v-model="goal" :options="goals" block /></Field>
    <div class="two">
      <Field :label="GOALS[goal].costName"><MoneyInput v-model="target" placeholder="vd 150k" /></Field>
      <Field label="Trần ngân sách khi tăng"><MoneyInput v-model="cap" placeholder="vd 2tr" /></Field>
    </div>
    <ul class="list">
      <li v-for="x in rules" :key="x.key" :class="{ off: !picked[x.key] }">
        <label><input v-model="picked[x.key]" type="checkbox" /><b>{{ x.title }}</b></label>
        <p class="why">{{ x.why }}</p>
        <p class="desc">{{ describeRule(x.r).join(' ') }}</p>
      </li>
    </ul>
    <p v-if="firstError" class="err">{{ firstError }}</p>
    <template #footer>
      <Btn @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :loading="busy" :disabled="!count" @click="create">Tạo {{ count }} rule</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.two { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.list li { padding: 12px 14px; border-radius: var(--r-md); background: var(--surface-2); transition: opacity .15s; }
.list li.off { opacity: .55; }
.list label { display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; }
.why { margin: 4px 0 0 24px; font-size: 13px; color: var(--text-2); line-height: 1.45; }
.desc { margin: 4px 0 0 24px; font-size: 12.5px; color: var(--text-3); line-height: 1.45; }
.err { margin: 10px 0 0; color: var(--danger); font-size: 13px; }
@media (max-width: 620px) { .two { grid-template-columns: 1fr; } }
</style>
