<script setup>
import { ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import { api } from '../lib/api'
import { DAY_LABEL, DAY_ORDER } from '../lib/constants'
import { toast } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Segmented from './Segmented.vue'
import TargetPicker from './TargetPicker.vue'

const props = defineProps({ modelValue: Boolean, item: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'saved'])

const blank = () => ({ name: '', action: 'on', time: '06:00', days: [0, 1, 2, 3, 4, 5, 6], targets: [], mode: 'percent', value: 20, enabled: true })
const f = ref(blank())

watch(() => props.modelValue, (open) => { if (open) f.value = { ...blank(), ...JSON.parse(JSON.stringify(props.item || {})) } })

const toggleDay = (d) => { const s = new Set(f.value.days); s.has(d) ? s.delete(d) : s.add(d); f.value.days = [...s] }
const setDays = (arr) => { f.value.days = arr }

async function save() {
  const v = f.value
  if (!v.targets.length) return toast('Hãy chọn ít nhất 1 chiến dịch', 'error')
  if (!v.days.length) return toast('Hãy chọn ít nhất 1 ngày', 'error')
  if (v.action === 'budget' && !Number(v.value)) return toast('Nhập giá trị ngân sách', 'error')
  await api('schedules', 'POST', { ...v, name: v.name.trim() || 'Lịch mới', value: Number(v.value) })
  toast('Đã lưu lịch')
  emit('saved'); emit('update:modelValue', false)
}
const actions = [{ value: 'on', label: 'Bật camp' }, { value: 'off', label: 'Tắt camp' }, { value: 'budget', label: 'Đổi ngân sách' }]
const modes = [{ value: 'percent', label: 'Theo %' }, { value: 'set', label: 'Số tiền cố định' }]
</script>

<template>
  <Modal :model-value="modelValue" :title="item && item.id ? 'Sửa lịch' : 'Thêm lịch'" subtitle="Tool sẽ chạy đúng giờ, kể cả khi bạn không mở trang này." @update:model-value="emit('update:modelValue', $event)">
    <Field label="Tên lịch"><input v-model="f.name" class="input" placeholder="Vd: Bật camp buổi sáng" /></Field>

    <div class="two">
      <Field label="Hành động"><Segmented v-model="f.action" :options="actions" block /></Field>
      <Field label="Giờ chạy"><input v-model="f.time" type="time" class="input" /></Field>
    </div>

    <Field v-if="f.action === 'budget'" label="Đổi ngân sách" hint="Theo %: nhập số âm để giảm (vd -30). Số tiền cố định: đơn vị tiền của tài khoản.">
      <div class="inl"><Segmented v-model="f.mode" :options="modes" /><input v-model="f.value" type="number" class="input val" /></div>
    </Field>

    <Field label="Ngày chạy">
      <div class="days">
        <button v-for="d in DAY_ORDER" :key="d" type="button" class="day" :class="{ on: f.days.includes(d) }" @click="toggleDay(d)">{{ DAY_LABEL[d] }}</button>
      </div>
      <div class="quick">
        <button type="button" @click="setDays([0, 1, 2, 3, 4, 5, 6])">Hằng ngày</button>
        <button type="button" @click="setDays([1, 2, 3, 4, 5])">T2–T6</button>
        <button type="button" @click="setDays([6, 0])">Cuối tuần</button>
      </div>
    </Field>

    <Field label="Áp dụng cho"><TargetPicker v-model="f.targets" /></Field>

    <template #footer>
      <Btn @click="emit('update:modelValue', false)">Huỷ</Btn>
      <Btn variant="primary" :icon="Check" :action="save">Lưu lịch</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.two { display: grid; grid-template-columns: 1fr 150px; gap: 14px; }
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.val { width: 140px; }
.days { display: flex; gap: 6px; flex-wrap: wrap; }
.day { width: 46px; height: 40px; border-radius: 12px; border: 1px solid var(--border-strong); background: var(--surface); font-weight: 650; font-size: 13.5px; color: var(--text-2); transition: .15s var(--ease); }
.day:hover { border-color: var(--accent); color: var(--accent); }
.day.on { background: var(--accent-grad); border-color: transparent; color: #fff; box-shadow: 0 4px 12px -4px var(--accent-ring); }
.quick { display: flex; gap: 4px; margin-top: 8px; }
.quick button { border: 0; background: none; color: var(--accent); font-weight: 600; font-size: 13px; padding: 4px 8px; border-radius: 7px; }
.quick button:hover { background: var(--accent-soft); }
@media (max-width: 520px) { .two { grid-template-columns: 1fr; } .day { width: 42px; } }
</style>
