<script setup>
import { ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import { api } from '../lib/api'
import { METRICS } from '../lib/constants'
import { toast } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Segmented from './Segmented.vue'
import TargetPicker from './TargetPicker.vue'

const props = defineProps({ modelValue: Boolean, item: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])

const blank = () => ({ name: '', metric: 'cpa', op: '>', value: 150000, minSpend: 100000, action: 'pause', pct: 20, maxBudget: '', minBudget: '', cooldownHours: 24, from: '', to: '', allActive: true, level: 'campaign', targets: [], enabled: true })
const f = ref(blank())
const scope = ref('all')

watch(() => props.modelValue, (open) => {
  if (!open) return
  f.value = { ...blank(), ...JSON.parse(JSON.stringify(props.item || {})) }
  f.value.maxBudget = f.value.maxBudget || ''; f.value.minBudget = f.value.minBudget || ''
  scope.value = f.value.allActive ? 'all' : 'pick'
})

async function save() {
  const v = f.value, all = scope.value === 'all'
  if (!all && !v.targets.length) return toast('Hãy chọn camp áp dụng', 'error')
  if (v.action !== 'pause' && !Number(v.pct)) return toast('Nhập % thay đổi ngân sách', 'error')
  await api('rules', 'POST', {
    ...v, name: v.name.trim() || 'Rule mới', value: Number(v.value), minSpend: Number(v.minSpend) || 0, pct: Number(v.pct) || 0,
    maxBudget: Number(v.maxBudget) || 0, minBudget: Number(v.minBudget) || 0, cooldownHours: Number(v.cooldownHours) || 0,
    allActive: all, level: 'campaign', targets: all ? [] : v.targets,
  })
  toast('Đã lưu rule')
  emit('saved'); emit('update:modelValue', false)
}
const ops = [{ value: '>', label: 'Lớn hơn' }, { value: '<', label: 'Nhỏ hơn' }]
const actions = [{ value: 'pause', label: 'Tắt camp' }, { value: 'increase', label: 'Tăng ngân sách' }, { value: 'decrease', label: 'Giảm ngân sách' }]
const scopes = [{ value: 'all', label: 'Tất cả camp đang chạy' }, { value: 'pick', label: 'Chọn camp cụ thể' }]
</script>

<template>
  <Modal :model-value="modelValue" :title="item && item.id ? 'Sửa rule' : 'Thêm rule'" subtitle="Tool kiểm tra rule định kỳ dựa trên số liệu hôm nay." @update:model-value="emit('update:modelValue', $event)">
    <Field label="Tên rule"><input v-model="f.name" class="input" placeholder="Vd: Tắt camp CPA cao" /></Field>

    <Field label="Nếu (số liệu hôm nay)">
      <div class="inl">
        <select v-model="f.metric" class="input sel"><option v-for="(l, k) in METRICS" :key="k" :value="k">{{ l }}</option></select>
        <Segmented v-model="f.op" :options="ops" />
        <input v-model="f.value" type="number" step="any" class="input val" />
      </div>
    </Field>

    <Field label="Chỉ xét khi đã chi tiêu tối thiểu" hint="Tránh tắt nhầm khi camp mới chạy, chưa đủ dữ liệu."><input v-model="f.minSpend" type="number" class="input" style="max-width: 220px" /></Field>

    <Field label="Thì">
      <Segmented v-model="f.action" :options="actions" block />
      <div v-if="f.action !== 'pause'" class="adj">
        <label><span>Thay đổi</span><div class="with"><input v-model="f.pct" type="number" class="input" /><em>%</em></div></label>
        <label><span>Trần ngân sách</span><input v-model="f.maxBudget" type="number" class="input" placeholder="Không giới hạn" /></label>
        <label><span>Sàn ngân sách</span><input v-model="f.minBudget" type="number" class="input" placeholder="Không giới hạn" /></label>
      </div>
    </Field>

    <div class="two">
      <Field label="Không lặp lại cho cùng camp trong (giờ)"><input v-model="f.cooldownHours" type="number" class="input" /></Field>
      <Field label="Chỉ chạy trong khung giờ"><div class="inl"><input v-model="f.from" type="time" class="input" />→<input v-model="f.to" type="time" class="input" /></div></Field>
    </div>

    <Field label="Áp dụng cho">
      <Segmented v-model="scope" :options="scopes" block />
      <div v-if="scope === 'pick'" style="margin-top: 12px"><TargetPicker v-model="f.targets" level="campaign" /></div>
    </Field>

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
.adj label { display: block; } .adj span { display: block; font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 5px; }
.with { position: relative; } .with .input { padding-right: 30px; } .with em { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); font-style: normal; color: var(--text-3); }
@media (max-width: 620px) { .two, .adj { grid-template-columns: 1fr; } }
</style>
