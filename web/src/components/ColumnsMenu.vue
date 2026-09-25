<script setup>
// Chọn cột hiển thị trong bảng (giống mục "Cột" của Ads Manager). v-model = mảng key cột; luôn giữ ít nhất 1 cột.
import { ref, computed, watch } from 'vue'
import { Columns3, RotateCcw } from 'lucide-vue-next'
import Popover from './Popover.vue'
import { COLUMNS, DEFAULT_COLUMNS, cleanColumns } from '../lib/overviewColumns'

// Bản sao cục bộ ghi ngay khi bấm (props chỉ đổi sau khi cha vẽ lại), nên bấm liên tiếp nhiều ô không bị mất lần nào
const props = defineProps({ modelValue: { type: Array, required: true } })
const emit = defineEmits(['update:modelValue'])
const local = ref([...props.modelValue])
watch(() => props.modelValue, (v) => { local.value = [...v] })
const open = ref(false)
const on = computed(() => new Set(local.value))
const isDefault = computed(() => local.value.length === DEFAULT_COLUMNS.length && DEFAULT_COLUMNS.every((k) => on.value.has(k)))
const commit = (list) => { local.value = list; emit('update:modelValue', list) }

function toggle(key) {
  const next = new Set(on.value)
  if (next.has(key)) { if (next.size > 1) next.delete(key) } else next.add(key)
  commit(cleanColumns([...next]))
}
const reset = () => commit([...DEFAULT_COLUMNS])
</script>

<template>
  <Popover v-model="open" align="right" width="290px" label="Chọn cột hiển thị">
    <template #trigger="{ toggle: tg }">
      <button type="button" class="cbtn" :class="{ on: open }" aria-haspopup="dialog" :aria-expanded="open" @click="tg">
        <Columns3 :size="16" /><span class="lb">Cột</span><em class="n num">{{ local.length }}/{{ COLUMNS.length }}</em>
      </button>
    </template>
    <div class="cm">
      <p class="hd">Hiển thị trong bảng</p>
      <label v-for="c in COLUMNS" :key="c.key" class="row" :class="{ lock: on.has(c.key) && on.size === 1 }">
        <input type="checkbox" :checked="on.has(c.key)" :disabled="on.has(c.key) && on.size === 1" @change="toggle(c.key)" />
        <span>{{ c.menu || c.label }}</span>
      </label>
      <button type="button" class="reset" :disabled="isDefault" @click="reset"><RotateCcw :size="14" />Đặt lại mặc định</button>
    </div>
  </Popover>
</template>

<style scoped>
.cbtn {
  display: inline-flex; align-items: center; gap: 8px; min-height: 42px; padding: 6px 13px; border-radius: 12px; cursor: pointer;
  background: var(--surface); border: 1px solid var(--border-strong); color: var(--text-2); font: inherit; font-size: 14px; font-weight: 600; box-shadow: var(--shadow-sm);
  transition: border-color .15s, box-shadow .15s, color .15s;
}
.cbtn:hover, .cbtn.on { border-color: var(--accent); color: var(--accent); }
.cbtn.on { box-shadow: 0 0 0 4px var(--accent-soft); }
.n { font-style: normal; font-size: 11.5px; padding: 1px 7px; border-radius: 99px; background: var(--surface-3); color: var(--text-3); }
.cm { padding: 8px; }
.hd { margin: 4px 8px 6px; font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--text-3); }
.row { display: flex; align-items: center; gap: 11px; padding: 8px 10px; border-radius: 9px; cursor: pointer; font-size: 14px; }
.row:hover { background: var(--surface-2); }
.row input { accent-color: var(--accent); width: 17px; height: 17px; margin: 0; cursor: pointer; }
.row.lock { opacity: .6; cursor: not-allowed; } .row.lock input { cursor: not-allowed; }
.reset { display: inline-flex; align-items: center; gap: 7px; width: 100%; margin-top: 6px; padding: 9px 10px; border: 0; border-top: 1px solid var(--border); background: none; color: var(--accent); font: inherit; font-size: 13.5px; font-weight: 600; cursor: pointer; border-radius: 0 0 9px 9px; }
.reset:hover:not(:disabled) { background: var(--accent-soft); }
.reset:disabled { color: var(--text-3); cursor: default; }
@media (max-width: 640px) { .cbtn .lb { display: none; } }
</style>
