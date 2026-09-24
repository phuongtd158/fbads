<script setup>
import { ref, nextTick } from 'vue'
import { Pencil, Loader2 } from 'lucide-vue-next'
import { setObjBudget } from '../stores/app'
import { toast, toastError } from '../stores/ui'
import { fmt } from '../lib/format'

const props = defineProps({ o: { type: Object, required: true } })
const editing = ref(false)
const saving = ref(false)
const val = ref('')
const inp = ref(null)

function start() {
  val.value = Math.round(props.o.dailyBudget)
  editing.value = true
  nextTick(() => { if (inp.value) { inp.value.focus(); inp.value.select() } })
}
async function commit() {
  if (!editing.value || saving.value) return
  const v = Number(val.value)
  if (!(v > 0) || v === Math.round(props.o.dailyBudget)) { editing.value = false; return }
  saving.value = true
  try { await setObjBudget(props.o, v); toast(`Ngân sách mới: ${fmt(v)}`) } catch (e) { toastError(e) } finally { saving.value = false; editing.value = false }
}
</script>

<template>
  <span v-if="o.dailyBudget == null" class="none faint" title="Ngân sách đặt ở cấp khác (CBO)">–</span>
  <span v-else-if="saving" class="saving faint"><Loader2 class="spin" :size="14" />Đang lưu…</span>
  <input v-else-if="editing" ref="inp" v-model="val" class="input editing" type="number" step="10000" min="0"
    @keydown.enter.prevent="commit" @keydown.esc.prevent="editing = false" @blur="commit" />
  <button v-else class="bud num" title="Bấm để sửa ngân sách" @click="start">{{ fmt(o.dailyBudget) }}<Pencil :size="13" /></button>
</template>

<style scoped>
.bud { display: inline-flex; align-items: center; gap: 7px; border: 1px solid transparent; background: none; padding: 4px 9px; margin-right: -9px; border-radius: 9px; font-weight: 650; font-size: 15px; transition: .15s; }
.bud svg { opacity: 0; color: var(--accent); transition: opacity .15s; }
.bud:hover { background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 30%, transparent); color: var(--accent); }
.bud:hover svg { opacity: 1; }
.input { width: 130px; text-align: right; padding: 6px 10px; font-weight: 650; }
.saving { display: inline-flex; gap: 6px; align-items: center; font-size: 13.5px; }
</style>
