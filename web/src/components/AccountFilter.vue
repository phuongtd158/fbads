<script setup>
// Lọc theo tài khoản quảng cáo, chọn được nhiều tài khoản. v-model = mảng id; [] nghĩa là xem tất cả.
import { ref, computed } from 'vue'
import { Building2, ChevronDown, Search, AlertTriangle } from 'lucide-vue-next'
import Popover from './Popover.vue'

const props = defineProps({
  accounts: { type: Array, required: true },            // [{ id, name, currency }]
  counts: { type: Object, default: () => ({}) },        // { [id]: số chiến dịch }
  errIds: { type: Object, default: () => new Set() },   // tài khoản không tải được
  modelValue: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])
const open = ref(false)
const q = ref('')

const ids = computed(() => props.accounts.map((a) => a.id))
const picked = computed(() => props.modelValue.filter((id) => ids.value.includes(id)))
const everyone = computed(() => !picked.value.length || picked.value.length === ids.value.length)
const isOn = (id) => everyone.value || picked.value.includes(id)
const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return s ? props.accounts.filter((a) => a.name.toLowerCase().includes(s) || String(a.id).includes(s)) : props.accounts
})
const label = computed(() => {
  if (everyone.value) return `Tất cả tài khoản`
  if (picked.value.length === 1) { const a = props.accounts.find((x) => x.id === picked.value[0]); return a ? a.name : picked.value[0] }
  return `${picked.value.length} tài khoản`
})

// Chọn hết = không lọc ([]); luôn giữ ít nhất 1 tài khoản
function commit(list) {
  const keep = ids.value.filter((id) => list.includes(id))
  emit('update:modelValue', !keep.length || keep.length === ids.value.length ? [] : keep)
}
function toggle(id) {
  const cur = everyone.value ? [...ids.value] : [...picked.value]
  const next = cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]
  if (next.length) commit(next)
}
const only = (id) => emit('update:modelValue', ids.value.length > 1 ? [id] : [])
</script>

<template>
  <Popover v-model="open" label="Lọc theo tài khoản quảng cáo" width="340px">
    <template #trigger="{ toggle: tg }">
      <button type="button" class="abtn" :class="{ on: open, active: !everyone }" aria-haspopup="dialog" :aria-expanded="open" @click="tg">
        <Building2 :size="17" class="ci" />
        <span class="tx"><b>{{ label }}</b><em v-if="everyone">{{ accounts.length }} tài khoản</em><em v-else>trên {{ accounts.length }}</em></span>
        <ChevronDown :size="15" class="ch" />
      </button>
    </template>

    <div class="af">
      <div v-if="accounts.length > 6" class="sr"><Search :size="15" /><input v-model="q" class="input" placeholder="Tìm tài khoản…" aria-label="Tìm tài khoản" /></div>
      <label class="row all">
        <input type="checkbox" :checked="everyone" :indeterminate="!everyone" @change="emit('update:modelValue', [])" />
        <span class="nm"><b>Tất cả tài khoản</b></span>
      </label>
      <div class="list">
        <label v-for="a in shown" :key="a.id" class="row" :class="{ off: !isOn(a.id) }">
          <input type="checkbox" :checked="isOn(a.id)" @change="toggle(a.id)" />
          <span class="nm">
            <b :title="a.name">{{ a.name }}</b>
            <small class="faint">ID {{ a.id }}<template v-if="a.currency"> · {{ a.currency }}</template></small>
          </span>
          <span v-if="errIds.has(a.id)" class="er" title="Không tải được tài khoản này"><AlertTriangle :size="14" /></span>
          <span v-else class="ct num">{{ counts[a.id] || 0 }}</span>
          <button type="button" class="only" @click.prevent.stop="only(a.id)">Chỉ tài khoản này</button>
        </label>
        <p v-if="!shown.length" class="none faint">Không tìm thấy tài khoản.</p>
      </div>
    </div>
  </Popover>
</template>

<style scoped>
.abtn {
  display: inline-flex; align-items: center; gap: 10px; min-height: 42px; padding: 6px 12px; border-radius: 12px; cursor: pointer; max-width: 260px;
  background: var(--surface); border: 1px solid var(--border-strong); color: var(--text); font: inherit; text-align: left; box-shadow: var(--shadow-sm);
  transition: border-color .15s, box-shadow .15s, background .15s;
}
.abtn:hover, .abtn.on { border-color: var(--accent); }
.abtn.on { box-shadow: 0 0 0 4px var(--accent-soft); }
.abtn.active { background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 45%, var(--border-strong)); }
.ci { color: var(--accent); flex: none; } .ch { color: var(--text-3); flex: none; }
.tx { display: flex; flex-direction: column; line-height: 1.2; min-width: 0; }
.tx b { font-size: 14px; font-weight: 650; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tx em { font-style: normal; font-size: 12px; color: var(--text-3); white-space: nowrap; }

.af { padding: 8px; display: flex; flex-direction: column; gap: 4px; }
.sr { position: relative; margin: 2px 2px 6px; }
.sr svg { position: absolute; left: 11px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.sr .input { padding: 8px 10px 8px 33px; font-size: 14px; }
.row { display: flex; align-items: center; gap: 11px; padding: 9px 10px; border-radius: 10px; cursor: pointer; position: relative; }
.row:hover { background: var(--surface-2); }
.row input { accent-color: var(--accent); width: 17px; height: 17px; flex: none; margin: 0; cursor: pointer; }
.row.all { border-bottom: 1px solid var(--border); border-radius: 10px 10px 0 0; margin-bottom: 2px; }
.row.off .nm b { color: var(--text-3); }
.nm { flex: 1; min-width: 0; display: flex; flex-direction: column; line-height: 1.25; }
.nm b { font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nm small { font-size: 12px; }
.ct { font-size: 12px; font-weight: 650; color: var(--text-2); background: var(--surface-3); padding: 1px 8px; border-radius: 99px; flex: none; }
.er { color: var(--danger); display: grid; place-items: center; flex: none; }
.only { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); display: none; border: 1px solid var(--border-strong); background: var(--surface); color: var(--accent); font: inherit; font-size: 12px; font-weight: 650; padding: 3px 9px; border-radius: 8px; cursor: pointer; box-shadow: var(--shadow-sm); }
.row:hover .only, .row:focus-within .only { display: inline-block; }
.row:hover .ct, .row:hover .er { visibility: hidden; }
.list { max-height: 320px; overflow-y: auto; }
.none { padding: 14px; text-align: center; font-size: 14px; margin: 0; }
@media (max-width: 640px) { .abtn { max-width: none; width: 100%; } .only { display: none !important; } .row:hover .ct, .row:hover .er { visibility: visible; } }
</style>
