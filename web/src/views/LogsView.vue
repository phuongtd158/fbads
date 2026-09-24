<script setup>
import { ref, computed, onMounted } from 'vue'
import { RefreshCw, CheckCircle2, AlertCircle, FlaskConical, ScrollText, Search } from 'lucide-vue-next'
import { api } from '../lib/api'
import { toastError } from '../stores/ui'
import { timeOf, dayLabel } from '../lib/format'
import Btn from '../components/Btn.vue'
import Segmented from '../components/Segmented.vue'
import EmptyState from '../components/EmptyState.vue'
import Skeleton from '../components/Skeleton.vue'

const logs = ref([])
const loaded = ref(false)
const filter = ref('all')
const q = ref('')

async function load() { try { logs.value = await api('logs') } catch (e) { toastError(e) } finally { loaded.value = true } }
onMounted(load)

const kind = (l) => (l.ok === false ? 'err' : l.dry ? 'dry' : 'ok')
const options = computed(() => [
  { value: 'all', label: 'Tất cả', count: logs.value.length },
  { value: 'ok', label: 'Thành công', count: logs.value.filter((l) => kind(l) === 'ok').length },
  { value: 'dry', label: 'Chạy thử', count: logs.value.filter((l) => kind(l) === 'dry').length },
  { value: 'err', label: 'Lỗi', count: logs.value.filter((l) => kind(l) === 'err').length },
])
const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return logs.value.filter((l) => (filter.value === 'all' || kind(l) === filter.value) && (!s || `${l.source} ${l.name} ${l.detail}`.toLowerCase().includes(s)))
})
const groups = computed(() => {
  const g = {}
  for (const l of shown.value) (g[dayLabel(l.ts)] ||= []).push(l)
  return Object.entries(g)
})
const icon = (l) => ({ ok: CheckCircle2, dry: FlaskConical, err: AlertCircle }[kind(l)])
</script>

<template>
  <div>
    <Teleport to="#page-actions" defer><Btn :icon="RefreshCw" :action="load">Làm mới</Btn></Teleport>

    <div class="tb">
      <Segmented v-model="filter" :options="options" />
      <div class="search"><Search :size="16" /><input v-model="q" class="input" placeholder="Tìm trong nhật ký…" /></div>
    </div>

    <section v-if="!loaded" class="card pad"><div v-for="i in 5" :key="i" class="sk"><Skeleton h="40px" r="12px" /></div></section>
    <section v-else-if="!shown.length" class="card"><EmptyState :icon="ScrollText" :title="logs.length ? 'Không có mục phù hợp' : 'Chưa có hoạt động'" :text="logs.length ? 'Thử đổi bộ lọc hoặc từ khoá.' : 'Khi lịch hoặc rule chạy, mọi thay đổi sẽ hiện ở đây.'" /></section>
    <section v-else class="card feed">
      <template v-for="[day, items] in groups" :key="day">
        <div class="day">{{ day }}</div>
        <div v-for="(l, i) in items" :key="l.ts + i" class="lg">
          <span class="ic" :class="kind(l)"><component :is="icon(l)" :size="18" /></span>
          <div class="bd">
            <b>{{ l.source }}</b>
            <p><span class="nm">{{ l.name }}</span> <span class="muted">— {{ l.detail }}</span></p>
          </div>
          <time class="num faint">{{ timeOf(l.ts) }}</time>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.tb { display: flex; gap: 14px; align-items: center; justify-content: space-between; flex-wrap: wrap; margin-bottom: 16px; }
.search { position: relative; flex: 1 1 220px; max-width: 300px; }
.search svg { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.search .input { padding-left: 36px; }
.sk { margin-bottom: 12px; }
.feed { overflow: hidden; animation: fadeUp .4s var(--ease); }
.day { padding: 11px 22px; font-size: 12.5px; font-weight: 700; color: var(--text-3); background: var(--surface-2); text-transform: capitalize; letter-spacing: .02em; position: sticky; top: 0; }
.lg { display: flex; gap: 14px; align-items: flex-start; padding: 15px 22px; border-bottom: 1px solid var(--border); transition: background .15s; }
.lg:hover { background: var(--surface-2); } .lg:last-child { border-bottom: 0; }
.ic { width: 36px; height: 36px; border-radius: 12px; display: grid; place-items: center; flex: none; }
.ic.ok { background: var(--success-soft); color: var(--success); } .ic.dry { background: var(--warning-soft); color: var(--warning); } .ic.err { background: var(--danger-soft); color: var(--danger); }
.bd { flex: 1; min-width: 0; } .bd b { font-size: 14.5px; font-weight: 620; } .bd p { font-size: 14.5px; margin-top: 1px; overflow-wrap: anywhere; }
.nm { font-weight: 550; }
time { font-size: 13px; white-space: nowrap; padding-top: 2px; }
</style>
