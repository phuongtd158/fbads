<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { LayoutDashboard, CalendarClock, Zap, ScrollText, Settings, TrendingUp, LogOut, Search, BookOpen } from 'lucide-vue-next'
import { state, logout } from '../stores/app'
import { palette, toastError } from '../stores/ui'

const route = useRoute()
const nav = computed(() => [
  { to: '/', name: 'overview', label: 'Tổng quan', icon: LayoutDashboard },
  { to: '/schedules', name: 'schedules', label: 'Lịch tự động', icon: CalendarClock, count: state.schedules.length },
  { to: '/rules', name: 'rules', label: 'Rule hiệu quả', icon: Zap, count: state.rules.length },
  { to: '/logs', name: 'logs', label: 'Nhật ký', icon: ScrollText },
  { to: '/settings', name: 'settings', label: 'Cài đặt', icon: Settings },
  { to: '/help', name: 'help', label: 'Hướng dẫn', icon: BookOpen },
])

// Trạng thái kết nối ở chân sidebar
const status = computed(() => {
  const s = state.settings
  if (s.mock) return { tone: 'warning', title: 'Dùng thử', sub: 'Dữ liệu giả — chưa kết nối' }
  if (state.connChecking && !state.conn) return { tone: 'info', title: 'Đang kiểm tra…', sub: 'Kết nối Facebook' }
  if (!state.conn) return { tone: 'info', title: 'Chưa kiểm tra', sub: 'Facebook' }
  if (!state.conn.ok) return { tone: 'danger', title: 'Mất kết nối', sub: 'Bấm để sửa' }
  return { tone: s.dryRun ? 'info' : 'success', title: s.dryRun ? 'Chạy thử' : 'Chạy thật', sub: state.conn.name || 'Đã kết nối' }
})
async function doLogout() { try { await logout() } catch (e) { toastError(e) } }
</script>

<template>
  <aside>
    <div class="brand"><span class="logo"><TrendingUp :size="18" /></span><b>FB Ads Auto</b></div>

    <button class="find" @click="palette = true"><Search :size="15" /><span>Tìm nhanh…</span><kbd>Ctrl K</kbd></button>

    <nav>
      <RouterLink v-for="n in nav" :key="n.name" :to="n.to" class="item" :class="{ on: route.name === n.name }">
        <component :is="n.icon" :size="19" />
        <span>{{ n.label }}</span>
        <em v-if="n.count" class="num">{{ n.count }}</em>
      </RouterLink>
    </nav>

    <div class="grow" />

    <RouterLink to="/settings/connection" class="status" :class="status.tone">
      <i class="dot" />
      <div><b>{{ status.title }}</b><small>{{ status.sub }}</small></div>
    </RouterLink>
    <button v-if="state.auth.required" class="item out" @click="doLogout"><LogOut :size="18" /><span>Đăng xuất</span></button>
  </aside>
</template>

<style scoped>
aside {
  position: sticky; top: 14px; height: calc(100vh - 28px); margin: 14px 0 14px 14px; padding: 18px 12px 12px; display: flex; flex-direction: column; gap: 4px;
  background: var(--glass); backdrop-filter: blur(18px) saturate(1.4); border: 1px solid var(--border); border-radius: var(--r-xl);
  box-shadow: var(--shadow-md), var(--inset);
}
.brand { display: flex; align-items: center; gap: 11px; padding: 2px 10px 16px; font-size: 16.5px; letter-spacing: -.02em; }
.logo { width: 34px; height: 34px; border-radius: 11px; background: var(--accent-grad); color: #fff; display: grid; place-items: center; box-shadow: 0 6px 16px -4px var(--accent-ring); }
.find {
  display: flex; align-items: center; gap: 9px; margin: 0 2px 12px; padding: 9px 11px; border-radius: 12px; border: 1px solid var(--border);
  background: var(--surface-2); color: var(--text-3); font-size: 14px; transition: .15s;
}
.find:hover { border-color: var(--border-strong); color: var(--text-2); }
.find span { flex: 1; text-align: left; }
nav { display: flex; flex-direction: column; gap: 3px; }
.item {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 12px; color: var(--text-2); text-decoration: none;
  font-weight: 600; font-size: 14.5px; border: 0; background: none; width: 100%; text-align: left; position: relative; transition: background .18s, color .18s;
}
.item:hover { background: var(--surface-3); color: var(--text); }
.item.on { background: var(--accent-soft); color: var(--accent); }
.item.on::before { content: ''; position: absolute; left: -12px; top: 9px; bottom: 9px; width: 4px; border-radius: 0 4px 4px 0; background: var(--accent-grad); }
.item em { margin-left: auto; font-style: normal; font-size: 12px; padding: 0 8px; border-radius: 99px; background: var(--surface-3); color: var(--text-2); }
.item.on em { background: var(--accent-soft); color: var(--accent); }
.grow { flex: 1; }
.out { color: var(--text-3); }
.out:hover { color: var(--danger); background: var(--danger-soft); }
.status { display: flex; gap: 11px; align-items: center; padding: 11px 13px; border-radius: 14px; background: var(--surface-2); border: 1px solid var(--border); text-decoration: none; color: var(--text); margin-bottom: 4px; transition: .15s; }
.status:hover { border-color: var(--border-strong); }
.status b { display: block; font-size: 13.5px; line-height: 1.3; }
.status small { display: block; color: var(--text-3); font-size: 12.5px; line-height: 1.3; max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot { width: 9px; height: 9px; border-radius: 50%; flex: none; background: var(--text-3); }
.success .dot { background: var(--success); box-shadow: 0 0 0 4px var(--success-soft); }
.warning .dot { background: var(--warning); box-shadow: 0 0 0 4px var(--warning-soft); }
.info .dot { background: var(--info); box-shadow: 0 0 0 4px var(--info-soft); }
.danger .dot { background: var(--danger); box-shadow: 0 0 0 4px var(--danger-soft); animation: blink 1.6s infinite; }
@keyframes blink { 50% { opacity: .45; } }
</style>
