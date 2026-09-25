<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { RefreshCw, CheckCircle2, AlertCircle, FlaskConical, ScrollText, Search, ChevronRight, ChevronDown, Bell, SkipForward, X } from 'lucide-vue-next'
import { api } from '../lib/api'
import { state, loadState } from '../stores/app'
import { toastError } from '../stores/ui'
import { timeOf, dayLabel } from '../lib/format'
import { kindOf, KIND_LABEL } from '../lib/logHints'
import { accountLabel } from '../lib/accounts'
import Btn from '../components/Btn.vue'
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

async function load({ silent = false } = {}) {
  try { logs.value = await api('logs', 'GET', undefined, { bg: silent }) } catch (e) { if (!silent) toastError(e) } finally { loaded.value = true }
}
// Quay lại tab/cửa sổ thì tự tải lại (lịch/rule có thể vừa chạy)
const onVisible = () => { if (!document.hidden && loaded.value) load({ silent: true }) }
onMounted(() => {
  load()
  if (!state.schedules.length && !state.rules.length) loadState().catch(() => {})
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisible))

// ----- Trạng thái của 1 dòng -----
const STATUS = {
  ok: { label: 'Thành công', icon: CheckCircle2 },
  info: { label: 'Cảnh báo', icon: Bell },
  dry: { label: 'Chạy thử', icon: FlaskConical },
  skip: { label: 'Bỏ qua', icon: SkipForward },
  err: { label: 'Lỗi', icon: AlertCircle },
}
const kind = (l) => (l.ok === false ? 'err' : l.skipped ? 'skip' : l.action && l.action.type === 'notify' ? 'info' : l.dry ? 'dry' : 'ok')
const counts = computed(() => { const c = { ok: 0, info: 0, dry: 0, skip: 0, err: 0 }; for (const l of logs.value) c[kind(l)]++; return c })
const statusOptions = computed(() => [{ value: 'all', label: 'Tất cả', count: logs.value.length }, ...Object.entries(STATUS).map(([value, s]) => ({ value, label: s.label, count: counts.value[value] }))])
const sources = computed(() => [{ value: 'all', label: 'Mọi nguồn' }, ...Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label }))])

// ----- Nội dung 1 dòng: đối tượng là chính, kết quả ở dưới, nguồn/tài khoản là nhãn phụ -----
const SHORT = { schedule: 'Lịch', rule: 'Rule', manual: 'Thủ công', undo: 'Hoàn tác', system: 'Hệ thống' }
const titleOf = (l) => (l.target && l.target.name) || (l.name && l.name !== '-' ? l.name : '') || l.detail || l.source
const showDetail = (l) => titleOf(l) !== l.detail
// Nguồn ngắn gọn: "Rule · Tăng" (nhật ký cũ chưa ghi refName thì dùng chữ nguồn gốc, bỏ phần [..] lặp lại kết quả)
const sourceOf = (l) => {
  const k = kindOf(l)
  if (l.refName) return `${SHORT[k]} · ${l.refName}`
  return String(l.source || SHORT[k] || '').replace(/\s*\[.*\]\s*$/, '')
}
const accOf = (l) => {
  const t = l.target
  if (!t) return ''
  if (t.accountId) return t.accountName || t.accountId
  const o = state.objs.find((x) => x.id === t.id)
  return o ? accountLabel(o) : ''
}
const multiAcc = computed(() => ((state.objsMeta && state.objsMeta.accounts) || []).length > 1 || new Set(logs.value.map((l) => l.target && l.target.accountId).filter(Boolean)).size > 1)
const codeOf = (l) => (l.ok === false && l.error && (l.error.code || l.error.network) ? (l.error.network ? 'Lỗi mạng' : `Facebook #${l.error.code}${l.error.subcode ? '/' + l.error.subcode : ''}`) : '')

const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return logs.value.filter((l) => (filter.value === 'all' || kind(l) === filter.value) && (source.value === 'all' || kindOf(l) === source.value)
    && (!s || `${l.source} ${l.name} ${l.detail} ${(l.target && l.target.name) || ''} ${(l.error && l.error.code) || ''} ${multiAcc.value ? accOf(l) : ''}`.toLowerCase().includes(s)))
})

// ----- Gộp theo lượt chạy: một lần lịch/rule chạy ghi nhiều dòng liền nhau (vd rule khớp 48 camp) -----
// Gộp khi ≥ 3 dòng cùng lịch/rule, dòng sau cách dòng trước ≤ 3 phút, cùng ngày.
const RUN_GAP = 3 * 60e3, RUN_MIN = 3
function runsOf(list) {
  const out = []
  let cur = null
  for (const l of list) {
    const k = kindOf(l), t = +new Date(l.ts)
    const key = (k === 'rule' || k === 'schedule') && l.refId ? `${k}:${l.refId}` : ''
    if (key && cur && cur.key === key && cur.last - t <= RUN_GAP) { cur.items.push(l); cur.last = t; continue }
    cur = { key, items: [l], last: t }
    out.push(cur)
  }
  return out.flatMap((r) => (r.key && r.items.length >= RUN_MIN ? [{ run: true, id: `${r.key}@${r.items[0].ts}`, items: r.items }] : r.items.map((l) => ({ run: false, l }))))
}
const ORDER = ['err', 'ok', 'info', 'dry', 'skip']
function runInfo(items) {
  const c = {}
  for (const l of items) { const k = kind(l); c[k] = (c[k] || 0) + 1 }
  const ks = ORDER.filter((k) => c[k])
  const first = items[0], k = kindOf(first)
  const t0 = timeOf(items[items.length - 1].ts).slice(0, 5), t1 = timeOf(first.ts).slice(0, 5)
  return {
    status: c.err ? 'err' : ks.length === 1 ? ks[0] : 'ok',
    title: `${SHORT[k]} “${first.refName || sourceOf(first)}”`,
    summary: ks.map((x) => `${c[x]} ${STATUS[x].label.toLowerCase()}`).join(' · '),
    names: [...new Set(items.map(titleOf))].slice(0, 3).join(', ') + (items.length > 3 ? '…' : ''),
    time: t0 === t1 ? t1 : `${t0}–${t1}`,
    // cả lượt cùng một tài khoản / cùng cấp nhóm QC: ghi 1 lần ở đầu khối thay vì lặp ở từng mục
    acc: multiAcc.value && new Set(items.map(accOf)).size === 1 ? accOf(first) : '',
    adset: items.every((l) => l.target && l.target.level === 'adset'),
  }
}
const expanded = reactive(new Set())
const isOpen = (r) => !!q.value.trim() || expanded.has(r.id) // đang tìm thì mở sẵn để thấy mục khớp
const toggle = (r) => { expanded.has(r.id) ? expanded.delete(r.id) : expanded.add(r.id) }

// Danh sách phẳng (tiêu đề ngày + mục) để vẽ từng phần: danh sách dài (tối đa 300 dòng) không làm giật
const PAGE = 60
const cap = ref(PAGE)
watch([filter, source, q], () => { cap.value = PAGE })
const feed = computed(() => {
  const days = new Map()
  for (const l of shown.value) { const d = dayLabel(l.ts); if (!days.has(d)) days.set(d, []); days.get(d).push(l) }
  const out = []
  for (const [day, items] of days) { out.push({ day }); for (const e of runsOf(items)) out.push(e.run ? { ...e, info: runInfo(e.items) } : e) }
  return out
})
const entryCount = computed(() => feed.value.filter((e) => !e.day).length)
const visibleFeed = computed(() => {
  const out = []
  let n = 0
  for (const e of feed.value) {
    if (!e.day && ++n > cap.value) break
    out.push(e)
  }
  if (out.length && out[out.length - 1].day) out.pop()
  return out
})

const icon = (k) => STATUS[k].icon
const view = (l) => { current.value = l; open.value = true }
const clearAll = () => { filter.value = 'all'; source.value = 'all'; q.value = '' }
const filtered = computed(() => filter.value !== 'all' || source.value !== 'all' || !!q.value.trim())
</script>

<template>
  <div>
    <Teleport to="#page-actions" defer><Btn :icon="RefreshCw" :action="load">Làm mới</Btn></Teleport>

    <div class="tb">
      <div class="fchips" role="group" aria-label="Lọc theo kết quả">
        <button v-for="o in statusOptions" :key="o.value" type="button" class="fc" :class="[o.value, { on: filter === o.value, zero: !o.count }]"
          :aria-pressed="filter === o.value" :disabled="!o.count && filter !== o.value" @click="filter = o.value">
          <i v-if="o.value !== 'all'" class="dot" />{{ o.label }}<em class="num">{{ o.count }}</em>
        </button>
      </div>
      <div class="frow">
        <select v-model="source" class="input src" aria-label="Lọc theo nguồn"><option v-for="s in sources" :key="s.value" :value="s.value">{{ s.label }}</option></select>
        <div class="search">
          <Search :size="16" /><input v-model="q" class="input" placeholder="Tìm camp, rule, lỗi…" aria-label="Tìm trong nhật ký" />
          <button v-if="q" type="button" class="clr" aria-label="Xoá từ khoá" @click="q = ''"><X :size="15" /></button>
        </div>
      </div>
    </div>

    <section v-if="!loaded" class="card pad"><div v-for="i in 5" :key="i" class="sk"><Skeleton h="44px" r="12px" /></div></section>
    <section v-else-if="!shown.length" class="card">
      <EmptyState :icon="ScrollText" :title="logs.length ? 'Không có mục phù hợp' : 'Chưa có hoạt động'" :text="logs.length ? 'Thử đổi bộ lọc hoặc từ khoá.' : 'Khi lịch hoặc rule chạy, mọi thay đổi sẽ hiện ở đây.'">
        <Btn v-if="filtered" @click="clearAll">Xoá bộ lọc</Btn>
      </EmptyState>
    </section>
    <section v-else class="card feed">
      <template v-for="e in visibleFeed" :key="e.day ? 'd:' + e.day : e.run ? e.id : e.l.id || e.l.ts">
        <div v-if="e.day" class="day">{{ e.day }}</div>

        <!-- Một lượt chạy (gộp) -->
        <template v-else-if="e.run">
          <div class="lg run" :class="{ open: isOpen(e), err: e.info.status === 'err' }" role="button" tabindex="0" :aria-expanded="isOpen(e)" @click="toggle(e)" @keydown.enter="toggle(e)">
            <span class="ic" :class="e.info.status"><component :is="icon(e.info.status)" :size="17" /></span>
            <div class="bd">
              <div class="l1"><b class="tt">{{ e.info.title }}</b><time class="num">{{ e.info.time }}</time></div>
              <p class="dt"><b class="n num">{{ e.items.length }} mục</b> · {{ e.info.summary }}</p>
              <p v-if="!isOpen(e)" class="names faint">{{ e.info.names }}</p>
              <div v-if="e.info.acc || e.info.adset" class="mt"><span v-if="e.info.adset" class="tag">Nhóm QC</span><span v-if="e.info.acc" class="tag">{{ e.info.acc }}</span></div>
            </div>
            <ChevronDown :size="17" class="go" :class="{ up: isOpen(e) }" />
          </div>
          <div v-if="isOpen(e)" class="kids">
            <div v-for="l in e.items" :key="l.id || l.ts" class="lg kid" :class="{ err: l.ok === false }" role="button" tabindex="0" :aria-label="'Xem chi tiết: ' + titleOf(l)" @click="view(l)" @keydown.enter="view(l)">
              <span class="ic sm" :class="kind(l)"><component :is="icon(kind(l))" :size="14" /></span>
              <div class="bd">
                <div class="l1"><b class="tt">{{ titleOf(l) }}</b><time class="num">{{ timeOf(l.ts) }}</time></div>
                <p v-if="showDetail(l)" class="dt">{{ l.detail }}</p>
                <div v-if="(multiAcc && accOf(l) && !e.info.acc) || (l.target && l.target.level === 'adset' && !e.info.adset) || l.undone || codeOf(l)" class="mt">
                  <span v-if="l.target && l.target.level === 'adset' && !e.info.adset" class="tag">Nhóm QC</span>
                  <span v-if="multiAcc && accOf(l) && !e.info.acc" class="tag">{{ accOf(l) }}</span>
                  <span v-if="l.undone" class="tag">Đã hoàn tác</span>
                  <span v-if="codeOf(l)" class="tag danger">{{ codeOf(l) }}</span>
                </div>
              </div>
              <ChevronRight :size="16" class="go" />
            </div>
          </div>
        </template>

        <!-- Một dòng -->
        <div v-else class="lg" :class="{ err: e.l.ok === false }" role="button" tabindex="0" :aria-label="'Xem chi tiết: ' + titleOf(e.l)" @click="view(e.l)" @keydown.enter="view(e.l)">
          <span class="ic" :class="kind(e.l)"><component :is="icon(kind(e.l))" :size="17" /></span>
          <div class="bd">
            <div class="l1"><b class="tt">{{ titleOf(e.l) }}</b><time class="num">{{ timeOf(e.l.ts) }}</time></div>
            <p v-if="showDetail(e.l)" class="dt">{{ e.l.detail }}</p>
            <div class="mt">
              <span class="from">{{ sourceOf(e.l) }}</span>
              <span v-if="e.l.target && e.l.target.level === 'adset'" class="tag">Nhóm QC</span>
              <span v-if="multiAcc && accOf(e.l)" class="tag">{{ accOf(e.l) }}</span>
              <span v-if="e.l.undone" class="tag">Đã hoàn tác</span>
              <span v-if="codeOf(e.l)" class="tag danger">{{ codeOf(e.l) }}</span>
            </div>
          </div>
          <ChevronRight :size="16" class="go" />
        </div>
      </template>

      <div v-if="entryCount > cap" class="morebar">
        <span class="faint">Đang hiện {{ cap }} / {{ entryCount }} mục</span>
        <Btn size="sm" @click="cap += PAGE">Xem thêm</Btn>
      </div>
    </section>

    <LogDetail v-model="open" :log="current" @retried="load" />
  </div>
</template>

<style scoped>
/* ----- Bộ lọc ----- */
.tb { display: flex; gap: 12px 16px; align-items: center; justify-content: space-between; flex-wrap: wrap; margin-bottom: 16px; }
.fchips { display: flex; flex-wrap: wrap; gap: 6px; }
.fc {
  display: inline-flex; align-items: center; gap: 7px; padding: 6px 11px; border-radius: 99px; border: 1px solid var(--border-strong);
  background: var(--surface); color: var(--text-2); font-size: 13.5px; font-weight: 600; white-space: nowrap; transition: .15s var(--ease);
}
.fc:hover:not(:disabled) { color: var(--text); border-color: color-mix(in srgb, var(--accent) 45%, var(--border-strong)); }
.fc.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.fc:disabled { opacity: .45; cursor: default; }
.fc em { font-style: normal; font-size: 12px; padding: 0 6px; border-radius: 99px; background: var(--surface-3); color: var(--text-3); }
.fc.on em { background: var(--accent); color: var(--on-accent, #fff); }
.fc.err:not(.zero) em { background: var(--danger-soft); color: var(--danger); }
.dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.fc.ok .dot { background: var(--success); } .fc.info .dot { background: var(--info); } .fc.dry .dot, .fc.skip .dot { background: var(--warning); } .fc.err .dot { background: var(--danger); }
.frow { display: flex; gap: 10px; }
.src { width: 150px; padding: 8px 34px 8px 12px; }
.search { position: relative; width: 260px; }
.search > svg { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.search .input { padding: 8px 34px 8px 36px; }
.clr { position: absolute; right: 6px; top: 50%; transform: translateY(-50%); display: grid; place-items: center; width: 26px; height: 26px; border: 0; border-radius: 8px; background: none; color: var(--text-3); }
.clr:hover { background: var(--surface-3); color: var(--text); }
.sk { margin-bottom: 12px; }

/* ----- Danh sách ----- */
.feed { overflow: hidden; animation: fadeUp .4s var(--ease); }
.day { padding: 10px 20px; font-size: 12.5px; font-weight: 700; color: var(--text-3); background: var(--surface-2); text-transform: capitalize; letter-spacing: .02em; border-bottom: 1px solid var(--border); }
.lg { display: flex; gap: 13px; align-items: flex-start; padding: 13px 18px 13px 20px; border-bottom: 1px solid var(--border); transition: background .15s; cursor: pointer; }
.lg:hover, .lg:focus-visible { background: var(--surface-2); outline-offset: -2px; }
.lg.err { box-shadow: inset 3px 0 0 var(--danger); }
.ic { width: 34px; height: 34px; border-radius: 11px; display: grid; place-items: center; flex: none; }
.ic.sm { width: 26px; height: 26px; border-radius: 8px; margin-top: 1px; }
.ic.ok { background: var(--success-soft); color: var(--success); } .ic.info { background: var(--info-soft); color: var(--info); }
.ic.dry, .ic.skip { background: var(--warning-soft); color: var(--warning); } .ic.err { background: var(--danger-soft); color: var(--danger); }
.bd { flex: 1; min-width: 0; display: grid; gap: 3px; }
.l1 { display: flex; align-items: baseline; gap: 12px; min-width: 0; }
.tt { flex: 1; min-width: 0; font-size: 14.5px; font-weight: 620; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
time { flex: none; font-size: 12.5px; color: var(--text-3); }
.dt { font-size: 14px; color: var(--text-2); line-height: 1.5; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.dt .n { color: var(--text); font-weight: 650; }
.names { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mt { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 3px; }
.from { font-size: 12.5px; font-weight: 600; color: var(--text-3); }
.tag { padding: 1px 8px; border-radius: 6px; background: var(--surface-3); color: var(--text-2); font-size: 12px; font-weight: 600; white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
.tag.danger { background: var(--danger-soft); color: var(--danger); border-radius: 99px; }
.go { flex: none; align-self: center; color: var(--text-3); transition: transform .2s var(--ease), color .15s; }
.lg:hover .go { color: var(--accent); }
.go.up { transform: rotate(180deg); }
/* lượt chạy mở ra: các mục con thụt vào, nền nhạt */
.run.open { background: var(--surface-2); border-bottom-color: transparent; }
.kids { background: var(--surface-2); border-bottom: 1px solid var(--border); padding: 0 0 6px; }
.kid { padding: 9px 18px 9px 67px; border-bottom: 0; }
.kid:hover { background: var(--surface-3); }
.kid .tt { font-size: 14px; font-weight: 600; }
.kid .dt { font-size: 13.5px; -webkit-line-clamp: 1; }
.morebar { display: flex; align-items: center; justify-content: center; gap: 14px; flex-wrap: wrap; padding: 14px 16px; font-size: 13.5px; }

@media (max-width: 820px) {
  .tb { display: grid; gap: 10px; }
  .search { flex: 1; width: auto; min-width: 0; }
  .src { width: 128px; flex: none; }
}
@media (max-width: 640px) {
  .lg { gap: 11px; padding: 12px 14px; }
  .ic { width: 30px; height: 30px; border-radius: 10px; }
  .ic.sm { width: 24px; height: 24px; }
  .kid { padding: 9px 14px 9px 55px; }
  .go { display: none; }
  .tt { font-size: 14px; } .dt { font-size: 13.5px; }
  .kid .dt { -webkit-line-clamp: 2; }
  .day { padding: 9px 14px; }
  .fchips { gap: 6px 5px; }
  .fc { padding: 5px 9px; gap: 5px; font-size: 13px; }
  .fc em { padding: 0 5px; font-size: 11.5px; }
}
</style>
