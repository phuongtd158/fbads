<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Search, LayoutDashboard, CalendarClock, Zap, ScrollText, Settings, RefreshCw, Sun, LogOut, Play, Megaphone, KeyRound, CornerDownLeft, BookOpen } from 'lucide-vue-next'
import { state, loadObjs, logout } from '../stores/app'
import { palette, toggleTheme, toast, toastError } from '../stores/ui'
import { api } from '../lib/api'
import { TOPICS } from '../lib/help'
import { accountLabel } from '../lib/accounts'

const router = useRouter()
const q = ref('')
const active = ref(0)
const input = ref(null)

const multiAcc = computed(() => ((state.objsMeta && state.objsMeta.accounts) || []).length > 1)
const go = (path) => () => router.push(path)
const actions = computed(() => [
  { group: 'Đi tới', title: 'Tổng quan', icon: LayoutDashboard, run: go('/') },
  { group: 'Đi tới', title: 'Lịch tự động', icon: CalendarClock, run: go('/schedules') },
  { group: 'Đi tới', title: 'Rule hiệu quả', icon: Zap, run: go('/rules') },
  { group: 'Đi tới', title: 'Nhật ký', icon: ScrollText, run: go('/logs') },
  { group: 'Đi tới', title: 'Cài đặt', icon: Settings, run: go('/settings') },
  { group: 'Đi tới', title: 'Kết nối Facebook', icon: KeyRound, run: go('/settings/connection') },
  { group: 'Đi tới', title: 'Hướng dẫn sử dụng', icon: BookOpen, run: go('/help') },
  { group: 'Thao tác', title: 'Làm mới số liệu', icon: RefreshCw, run: async () => { await loadObjs(true); toast('Đã cập nhật số liệu') } },
  { group: 'Thao tác', title: 'Kiểm tra rule ngay', icon: Play, run: async () => { await api('rules/run', 'POST'); toast('Đã kiểm tra rule — xem Nhật ký') } },
  { group: 'Thao tác', title: 'Đổi giao diện sáng / tối', icon: Sun, run: toggleTheme },
  ...(state.auth.required ? [{ group: 'Thao tác', title: 'Đăng xuất', icon: LogOut, run: logout }] : []),
  ...TOPICS.map((t) => ({ group: 'Hướng dẫn', title: t.title, icon: BookOpen, sub: t.summary, run: go('/help/' + t.id) })),
  ...state.objs.filter((o) => o.level === 'campaign').map((o) => ({
    group: 'Chiến dịch', title: o.name, icon: Megaphone, sub: (o.effective === 'ACTIVE' ? 'Đang chạy' : 'Tạm dừng') + (multiAcc.value ? ' · ' + accountLabel(o) : ''), hay: multiAcc.value ? accountLabel(o) : '',
    run: () => router.push({ path: '/', query: { q: o.name } }),
  })),
])
const results = computed(() => {
  const s = q.value.trim().toLowerCase()
  return actions.value.filter((a) => !s || a.title.toLowerCase().includes(s) || (a.hay || '').toLowerCase().includes(s)).slice(0, 12).map((a, n) => ({ ...a, n }))
})
const grouped = computed(() => {
  const out = []
  for (const r of results.value) {
    let g = out.find((x) => x.name === r.group)
    if (!g) out.push((g = { name: r.group, items: [] }))
    g.items.push(r)
  }
  return out
})

watch(palette, async (open) => {
  if (!open) return
  q.value = ''; active.value = 0
  await nextTick()
  if (input.value) input.value.focus()
})
watch(q, () => { active.value = 0 })

async function pick(r) {
  palette.value = false
  try { await r.run() } catch (e) { toastError(e) }
}
function onKey(e) {
  const n = results.value.length
  if (e.key === 'ArrowDown') { e.preventDefault(); active.value = (active.value + 1) % Math.max(1, n) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); active.value = (active.value - 1 + n) % Math.max(1, n) }
  else if (e.key === 'Enter' && results.value[active.value]) pick(results.value[active.value])
  else if (e.key === 'Escape') palette.value = false
}
</script>

<template>
  <Teleport to="body">
    <Transition name="pal">
      <div v-if="palette" class="ov" @mousedown.self="palette = false">
        <div class="box" role="dialog" aria-label="Tìm nhanh">
          <div class="in">
            <Search :size="19" />
            <input ref="input" v-model="q" placeholder="Gõ để tìm trang, thao tác, chiến dịch…" @keydown="onKey" />
            <kbd>Esc</kbd>
          </div>
          <div class="res">
            <template v-for="g in grouped" :key="g.name">
              <div class="grp">{{ g.name }}</div>
              <button v-for="r in g.items" :key="g.name + r.title" class="row" :class="{ on: r.n === active }" @mousemove="active = r.n" @click="pick(r)">
                <span class="ic"><component :is="r.icon" :size="17" /></span>
                <span class="t">{{ r.title }}</span>
                <small v-if="r.sub" class="faint">{{ r.sub }}</small>
                <CornerDownLeft v-if="r.n === active" :size="15" class="ret" />
              </button>
            </template>
            <p v-if="!results.length" class="none faint">Không có kết quả cho “{{ q }}”</p>
          </div>
          <div class="ft faint"><span><kbd>↑</kbd> <kbd>↓</kbd> chọn</span><span><kbd>Enter</kbd> mở</span><span><kbd>Esc</kbd> đóng</span></div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ov { position: fixed; inset: 0; z-index: 150; display: flex; justify-content: center; align-items: flex-start; padding: 12vh 16px 16px; background: rgba(8, 10, 20, .5); backdrop-filter: blur(6px); }
.box { width: min(600px, 100%); background: var(--surface); border: 1px solid var(--border-strong); border-radius: var(--r-xl); box-shadow: var(--shadow-lg); overflow: hidden; }
.in { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); color: var(--text-3); }
.in input { flex: 1; border: 0; outline: 0; background: none; font: inherit; font-size: 17px; color: var(--text); }
.res { max-height: 380px; overflow: auto; padding: 8px; }
.grp { font-size: 12px; font-weight: 700; color: var(--text-3); padding: 10px 12px 4px; letter-spacing: .03em; text-transform: uppercase; }
.row { display: flex; align-items: center; gap: 12px; width: 100%; border: 0; background: none; padding: 10px 12px; border-radius: 12px; text-align: left; font-size: 15px; }
.row.on { background: var(--accent-soft); }
.ic { width: 32px; height: 32px; border-radius: 10px; background: var(--surface-3); display: grid; place-items: center; color: var(--text-2); flex: none; }
.row.on .ic { background: var(--accent); color: #fff; }
.t { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 550; }
.ret { color: var(--accent); }
.none { padding: 28px; text-align: center; }
.ft { display: flex; gap: 18px; padding: 11px 20px; border-top: 1px solid var(--border); background: var(--surface-2); font-size: 12.5px; }
.pal-enter-active, .pal-leave-active { transition: opacity .18s; }
.pal-enter-active .box, .pal-leave-active .box { transition: transform .28s var(--ease); }
.pal-enter-from, .pal-leave-to { opacity: 0; }
.pal-enter-from .box, .pal-leave-to .box { transform: translateY(-14px) scale(.97); }
@media (max-width: 820px) { .ft { display: none; } .ov { padding-top: 8vh; } }
</style>
