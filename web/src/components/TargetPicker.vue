<script setup>
import { ref, computed } from 'vue'
import { Search, Check } from 'lucide-vue-next'
import { state } from '../stores/app'
import Skeleton from './Skeleton.vue'

const props = defineProps({ modelValue: { type: Array, default: () => [] }, level: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])
const q = ref('')

const list = computed(() => state.objs.filter((o) => (!props.level || o.level === props.level) && o.name.toLowerCase().includes(q.value.trim().toLowerCase())))
const selected = computed(() => new Set(props.modelValue))

function toggle(id) {
  const s = new Set(props.modelValue)
  s.has(id) ? s.delete(id) : s.add(id)
  emit('update:modelValue', [...s])
}
const selectAll = () => emit('update:modelValue', [...new Set([...props.modelValue, ...list.value.map((o) => o.id)])])
const clear = () => emit('update:modelValue', props.modelValue.filter((id) => !list.value.some((o) => o.id === id)))
</script>

<template>
  <div class="pick">
    <div class="bar">
      <div class="search"><Search :size="15" /><input v-model="q" class="input" placeholder="Tìm chiến dịch…" /></div>
      <button type="button" class="lnk" @click="selectAll">Chọn hết</button>
      <button type="button" class="lnk mut" @click="clear">Bỏ chọn</button>
    </div>
    <div class="list">
      <template v-if="state.objsLoading && !state.objs.length"><div v-for="i in 3" :key="i" class="it"><Skeleton h="16px" /></div></template>
      <button v-for="o in list" :key="o.id" type="button" class="it" :class="{ on: selected.has(o.id) }" @click="toggle(o.id)">
        <span class="box"><Check :size="13" /></span>
        <span class="nm">{{ o.name }}</span>
        <span class="tag">{{ o.level === 'campaign' ? 'Camp' : 'Nhóm QC' }}</span>
      </button>
      <p v-if="!list.length && !state.objsLoading" class="none faint">{{ state.objs.length ? 'Không tìm thấy.' : state.objsErr || 'Chưa có dữ liệu chiến dịch.' }}</p>
    </div>
    <div class="foot faint">Đã chọn <b>{{ modelValue.length }}</b></div>
  </div>
</template>

<style scoped>
.pick { border: 1px solid var(--border-strong); border-radius: var(--r-md); overflow: hidden; background: var(--surface); }
.bar { display: flex; gap: 10px; align-items: center; padding: 8px; background: var(--surface-2); border-bottom: 1px solid var(--border); }
.search { position: relative; flex: 1; }
.search svg { position: absolute; left: 11px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.search .input { padding: 7px 10px 7px 32px; font-size: 14px; }
.lnk { border: 0; background: none; color: var(--accent); font-weight: 600; font-size: 13px; padding: 4px 6px; border-radius: 6px; }
.lnk.mut { color: var(--text-3); } .lnk:hover { background: var(--surface-3); }
.list { max-height: 210px; overflow: auto; padding: 4px; }
.it { display: flex; width: 100%; align-items: center; gap: 11px; padding: 9px 10px; border: 0; background: none; border-radius: 9px; text-align: left; }
.it:hover { background: var(--surface-2); }
.box { width: 19px; height: 19px; border-radius: 6px; border: 1.5px solid var(--border-strong); display: grid; place-items: center; color: transparent; flex: none; transition: .15s; }
.on .box { background: var(--accent); border-color: var(--accent); color: #fff; }
.nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14.5px; }
.tag { font-size: 12px; color: var(--text-3); background: var(--surface-3); padding: 1px 8px; border-radius: 6px; }
.none { padding: 18px; text-align: center; font-size: 14px; }
.foot { padding: 7px 14px; font-size: 12.5px; border-top: 1px solid var(--border); background: var(--surface-2); }
</style>
