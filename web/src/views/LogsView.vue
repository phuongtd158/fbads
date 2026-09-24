<script setup>
import { ref, computed, onMounted } from 'vue'
import { RefreshCw, CheckCircle2, AlertCircle, FlaskConical, ScrollText, Search, ChevronRight } from 'lucide-vue-next'
import { api } from '../lib/api'
import { state, loadState } from '../stores/app'
import { toastError } from '../stores/ui'
import { timeOf, dayLabel } from '../lib/format'
import { kindOf, KIND_LABEL } from '../lib/logHints'
import Btn from '../components/Btn.vue'
import Segmented from '../components/Segmented.vue'
import EmptyState from '../components/EmptyState.vue'
import Skeleton from '../components/Skeleton.vue'
import LogDetail from '../components/LogDetail.vue'

const logs = ref([])
const loaded = ref(false)
const filter = ref('all')
const source = ref('all')
const q = ref('')
const open = ref(false)
const current = ref(null)

async function load() { try { logs.value = await api('logs') } catch (e) { toastError(e) } finally { loaded.value = true } }
onMounted(() => { load(); if (!state.schedules.length && !state.rules.length) loadState().catch(() => {}) })

const kind = (l) => (l.ok === false ? 'err' : l.dry ? 'dry' : 'ok')
const options = computed(() => [
  { value: 'all', label: 'Tất cả', count: logs.value.length },
  { value: 'ok', label: 'Thành công', count: logs.value.filter((l) => kind(l) === 'ok').length },
  { value: 'dry', label: 'Chạy thử', count: logs.value.filter((l) => kind(l) === 'dry').length },
  { value: 'err', label: 'Lỗi', count: logs.value.filter((l) => kind(l) === 'err').length },
])
const sources = computed(() => [{ value: 'all', label: 'Mọi nguồn' }, ...Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label }))])
const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return logs.value.filter((l) => (filter.value === 'all' || kind(l) === filter.value) && (source.value === 'all' || kindOf(l) === source.value)
    && (!s || `${l.source} ${l.name} ${l.detail} ${(l.error && l.error.code) || ''}`.toLowerCase().includes(s)))
})
const groups = computed(() => {
  const g = {}
  for (const l of shown.value) (g[dayLabel(l.ts)] ||= []).push(l)
  return Object.entries(g)
})
const icon = (l) => ({ ok: CheckCircle2, dry: FlaskConical, err: AlertCircle }[kind(l)])
const view = (l) => { current.value = l; open.value = true }
</script>

<template>
  <div>
    <Teleport to="#page-actions" defer><Btn :icon="RefreshCw" :action="load">Làm mới</Btn></Teleport>

    <div class="tb">
      <Segmented v-model="filter" :options="options" />
      <div class="right">
        <select v-model="source" class="input src" aria-label="Lọc theo nguồn"><option v-for="s in sources" :key="s.value" :value="s.value">{{ s.label }}</option></select>
        <div class="search"><Search :size="16" /><input v-model="q" class="input" placeholder="Tìm trong nhật ký…" /></div>
      </div>
    </div>

    <section v-if="!loaded" class="card pad"><div v-for="i in 5" :key="i" class="sk"><Skeleton h="40px" r="12px" /></div></section>
    <section v-else-if="!shown.length" class="card"><EmptyState :icon="ScrollText" :title="logs.length ? 'Không có mục phù hợp' : 'Chưa có hoạt động'" :text="logs.length ? 'Thử đổi bộ lọc hoặc từ khoá.' : 'Khi lịch hoặc rule chạy, mọi thay đổi sẽ hiện ở đây.'" /></section>
    <section v-else class="card feed">
      <template v-for="[day, items] in groups" :key="day">
        <div class="day">{{ day }}</div>
        <div v-for="(l, i) in items" :key="l.id || l.ts + i" class="lg" :class="{ err: l.ok === false }" role="button" tabindex="0" :aria-label="'Xem chi tiết: ' + l.source" @click="view(l)" @keydown.enter="view(l)">
          <span class="ic" :class="kind(l)"><component :is="icon(l)" :size="18" /></span>
          <div class="bd">
            <b>{{ l.source }}</b>
            <p><span class="nm">{{ l.name }}</span> <span class="muted">— {{ l.detail }}</span></p>
            <span v-if="l.ok === false && l.error && (l.error.code || l.error.network)" class="code">{{ l.error.network ? 'Lỗi mạng' : `Facebook #${l.error.code}${l.error.subcode ? '/' + l.error.subcode : ''}` }}</span>
          </div>
          <time class="num faint">{{ timeOf(l.ts) }}</time>
          <span class="more" :class="{ hot: l.ok === false }">{{ l.ok === false ? 'Xem lỗi' : 'Chi tiết' }}<ChevronRight :size="15" /></span>
        </div>
      </template>
    </section>

    <LogDetail v-model="open" :log="current" @retried="load" />
  </div>
</template>

<style scoped>
.tb { display: flex; gap: 14px; align-items: center; justify-content: space-between; flex-wrap: wrap; margin-bottom: 16px; }
.right { display: flex; gap: 10px; flex-wrap: wrap; }
.src { width: 150px; padding: 8px 34px 8px 12px; }
.search { position: relative; width: 260px; max-width: 100%; }
.search svg { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.search .input { padding: 8px 12px 8px 36px; }
.sk { margin-bottom: 12px; }
.feed { overflow: hidden; animation: fadeUp .4s var(--ease); }
.day { padding: 11px 22px; font-size: 12.5px; font-weight: 700; color: var(--text-3); background: var(--surface-2); text-transform: capitalize; letter-spacing: .02em; position: sticky; top: 0; }
.lg { display: flex; gap: 14px; align-items: flex-start; padding: 15px 22px; border-bottom: 1px solid var(--border); transition: background .15s; cursor: pointer; }
.lg:hover, .lg:focus-visible { background: var(--surface-2); outline-offset: -2px; } .lg:last-child { border-bottom: 0; }
.lg.err { box-shadow: inset 3px 0 0 var(--danger); }
.ic { width: 36px; height: 36px; border-radius: 12px; display: grid; place-items: center; flex: none; }
.ic.ok { background: var(--success-soft); color: var(--success); } .ic.dry { background: var(--warning-soft); color: var(--warning); } .ic.err { background: var(--danger-soft); color: var(--danger); }
.bd { flex: 1; min-width: 0; } .bd b { font-size: 14.5px; font-weight: 620; } .bd p { font-size: 14.5px; margin-top: 1px; overflow-wrap: anywhere; }
.nm { font-weight: 550; }
.code { display: inline-block; margin-top: 6px; padding: 1px 9px; border-radius: 99px; font-size: 12px; font-weight: 650; background: var(--danger-soft); color: var(--danger); }
time { font-size: 13px; white-space: nowrap; padding-top: 2px; }
.more { display: inline-flex; align-items: center; gap: 2px; align-self: center; font-size: 13px; font-weight: 600; color: var(--text-3); white-space: nowrap; padding: 4px 8px; border-radius: 9px; transition: .15s; }
.lg:hover .more { color: var(--accent); background: var(--accent-soft); } .more.hot { color: var(--danger); } .lg:hover .more.hot { color: var(--danger); background: var(--danger-soft); }
@media (max-width: 640px) { .more { display: none; } .lg { padding: 14px 16px; } .src { width: 130px; } }
</style>
