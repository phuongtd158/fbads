<script setup>
import { reactive, computed } from 'vue'
import { Save } from 'lucide-vue-next'
import { state, saveSettings } from '../../stores/app'
import { toast } from '../../stores/ui'
import { RESULT_ACTIONS } from '../../lib/constants'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'

const s = state.settings
const f = reactive({ resultAction: s.resultAction || 'purchase', timezone: s.timezone || 'Asia/Ho_Chi_Minh', ruleIntervalMin: s.ruleIntervalMin || 15 })
const options = computed(() => (RESULT_ACTIONS.some(([v]) => v === f.resultAction) ? RESULT_ACTIONS : [...RESULT_ACTIONS, [f.resultAction, f.resultAction]]))

async function save() {
  const changed = f.resultAction !== s.resultAction
  await saveSettings({ resultAction: f.resultAction, timezone: f.timezone.trim() || 'Asia/Ho_Chi_Minh', ruleIntervalMin: Math.max(5, Number(f.ruleIntervalMin) || 15) }, { reset: changed })
  toast('Đã lưu cài đặt chung')
}
</script>

<template>
  <section class="card pad">
    <h3>Chung</h3>
    <p class="muted sub">Cách tool tính kết quả và kiểm tra rule.</p>
    <div class="grid">
      <Field label="“Kết quả” dùng để tính CPA / ROAS" hint="Chọn đúng mục tiêu bạn chạy quảng cáo."><select v-model="f.resultAction" class="input"><option v-for="[v, l] in options" :key="v" :value="v">{{ l }}</option></select></Field>
      <Field label="Múi giờ" hint="Lịch chạy theo múi giờ này."><input v-model="f.timezone" class="input" /></Field>
      <Field label="Kiểm tra rule mỗi (phút)" hint="Tối thiểu 5 phút."><input v-model="f.ruleIntervalMin" class="input" type="number" min="5" /></Field>
    </div>
    <Btn variant="primary" :icon="Save" :action="save">Lưu</Btn>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.grid { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 14px; }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
