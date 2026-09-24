<script setup>
import { ref, nextTick } from 'vue'
import { Pencil, Loader2 } from 'lucide-vue-next'
import { setObjBudget } from '../stores/app'
import { toast, toastError, confirm } from '../stores/ui'
import { fmt } from '../lib/format'
import { validateBudget } from '../lib/validate'

const props = defineProps({ o: { type: Object, required: true } })
const editing = ref(false)
const saving = ref(false)
const val = ref('')
const error = ref('')
const inp = ref(null)
let working = false // chặn commit chạy hai lần (Enter rồi blur khi hộp xác nhận mở)

function start() {
  val.value = Math.round(props.o.dailyBudget)
  error.value = ''
  editing.value = true
  nextTick(() => { if (inp.value) { inp.value.focus(); inp.value.select() } })
}
function cancel() { editing.value = false; error.value = '' }

async function commit(fromBlur = false) {
  if (!editing.value || working) return
  working = true
  try {
    const r = validateBudget(val.value, props.o.dailyBudget)
    if (!r.ok) {
      if (fromBlur) { toast(r.error, 'error'); cancel() } else { error.value = r.error; nextTick(() => inp.value && inp.value.focus()) }
      return
    }
    if (r.value === Math.round(props.o.dailyBudget)) return cancel()
    // thay đổi lớn (gấp đôi trở lên hoặc giảm một nửa trở lên) → hỏi lại để tránh gõ nhầm số 0
    if (r.confirm && !await confirm('Xác nhận đổi ngân sách?', `${r.confirm} Bạn chắc chắn chứ?`, { ok: 'Đổi ngân sách' })) return cancel()
    error.value = ''
    saving.value = true
    try { await setObjBudget(props.o, r.value); toast(`Ngân sách mới: ${fmt(r.value)}`) } catch (e) { toastError(e) } finally { saving.value = false; editing.value = false }
  } finally { working = false }
}
</script>

<template>
  <span v-if="o.dailyBudget == null" class="none faint" title="Ngân sách đặt ở cấp khác (CBO)">–</span>
  <span v-else-if="saving" class="saving faint"><Loader2 class="spin" :size="14" />Đang lưu…</span>
  <span v-else-if="editing" class="ed">
    <input ref="inp" v-model="val" class="input editing" :class="{ bad: error }" type="number" step="10000" min="0" @keydown.enter.prevent="commit()" @keydown.esc.prevent="cancel" @input="error = ''" @blur="commit(true)" />
    <small v-if="error" class="berr" role="alert">{{ error }}</small>
  </span>
  <button v-else class="bud num" title="Bấm để sửa ngân sách" @click="start">{{ fmt(o.dailyBudget) }}<Pencil :size="13" /></button>
</template>

<style scoped>
.bud { display: inline-flex; align-items: center; gap: 7px; border: 1px solid transparent; background: none; padding: 4px 9px; margin-right: -9px; border-radius: 9px; font-weight: 650; font-size: 15px; transition: .15s; }
.bud svg { opacity: 0; color: var(--accent); transition: opacity .15s; }
.bud:hover { background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 30%, transparent); color: var(--accent); }
.bud:hover svg { opacity: 1; }
.ed { display: inline-flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.input { width: 130px; text-align: right; padding: 6px 10px; font-weight: 650; }
.input.bad { border-color: var(--danger); box-shadow: 0 0 0 3px var(--danger-soft); }
.berr { color: var(--danger); font-size: 12px; line-height: 1.35; max-width: 190px; text-align: right; }
.saving { display: inline-flex; gap: 6px; align-items: center; font-size: 13.5px; }
</style>
