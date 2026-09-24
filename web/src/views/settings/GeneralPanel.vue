<script setup>
import { reactive, ref, computed } from 'vue'
import { Save } from 'lucide-vue-next'
import { state, saveSettings } from '../../stores/app'
import { toast } from '../../stores/ui'
import { RESULT_ACTIONS } from '../../lib/constants'
import { validateSettings } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'

const s = state.settings
const f = reactive({ resultAction: s.resultAction || 'purchase', timezone: s.timezone || 'Asia/Ho_Chi_Minh', ruleIntervalMin: s.ruleIntervalMin || 15 })
const options = computed(() => (RESULT_ACTIONS.some(([v]) => v === f.resultAction) ? RESULT_ACTIONS : [...RESULT_ACTIONS, [f.resultAction, f.resultAction]]))
const submitted = ref(false)
const touched = reactive({})

// Cùng luật với server: múi giờ phải hợp lệ, chu kỳ rule 5–1440 phút (số nguyên)
const check = computed(() => validateSettings({ resultAction: f.resultAction, timezone: f.timezone, ruleIntervalMin: f.ruleIntervalMin }, s))
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')

async function save() {
  submitted.value = true
  if (!check.value.ok) { toast('Hãy sửa các mục báo lỗi trước khi lưu', 'error'); return }
  const changed = check.value.value.resultAction !== s.resultAction
  await saveSettings(check.value.value, { reset: changed })
  toast('Đã lưu cài đặt chung')
}
</script>

<template>
  <section class="card pad">
    <h3>Chung</h3>
    <p class="muted sub">Cách tool tính kết quả và kiểm tra rule.</p>
    <div class="grid">
      <Field label="“Kết quả” dùng để tính CPA / ROAS" tip="resultAction" :error="show('resultAction')" hint="Chọn đúng mục tiêu bạn chạy quảng cáo."><select v-model="f.resultAction" class="input"><option v-for="[v, l] in options" :key="v" :value="v">{{ l }}</option></select></Field>
      <Field label="Múi giờ" :error="show('timezone')" hint="Lịch chạy theo múi giờ này. Ví dụ: Asia/Ho_Chi_Minh."><input v-model="f.timezone" class="input" @blur="touched.timezone = true" /></Field>
      <Field label="Kiểm tra rule mỗi (phút)" tip="interval" :error="show('ruleIntervalMin')" hint="Từ 5 đến 1440 phút."><input v-model="f.ruleIntervalMin" class="input" type="number" min="5" max="1440" step="1" @blur="touched.ruleIntervalMin = true" /></Field>
    </div>
    <Btn variant="primary" :icon="Save" :action="save">Lưu</Btn>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.grid { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 14px; }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
