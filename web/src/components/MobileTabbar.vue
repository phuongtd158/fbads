<script setup>
import { useRoute } from 'vue-router'
import { LayoutDashboard, CalendarClock, Zap, ScrollText, Settings } from 'lucide-vue-next'

const route = useRoute()
const items = [
  { to: '/', name: 'overview', label: 'Tổng quan', icon: LayoutDashboard },
  { to: '/schedules', name: 'schedules', label: 'Lịch', icon: CalendarClock },
  { to: '/rules', name: 'rules', label: 'Rule', icon: Zap },
  { to: '/logs', name: 'logs', label: 'Nhật ký', icon: ScrollText },
  { to: '/settings', name: 'settings', label: 'Cài đặt', icon: Settings },
]
</script>

<template>
  <nav class="tabbar">
    <RouterLink v-for="i in items" :key="i.name" :to="i.to" :class="{ on: route.name === i.name }">
      <span class="pill"><component :is="i.icon" :size="20" /></span>
      <small>{{ i.label }}</small>
    </RouterLink>
  </nav>
</template>

<style scoped>
.tabbar {
  display: none; position: fixed; z-index: 40; left: 12px; right: 12px; bottom: calc(12px + env(safe-area-inset-bottom)); padding: 6px;
  background: var(--glass); backdrop-filter: blur(20px) saturate(1.5); border: 1px solid var(--border-strong); border-radius: 22px; box-shadow: var(--shadow-lg);
}
a { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 4px 2px; color: var(--text-3); text-decoration: none; font-weight: 600; }
.pill { width: 46px; height: 30px; border-radius: 99px; display: grid; place-items: center; transition: .25s var(--ease); }
small { font-size: 11px; }
a.on { color: var(--accent); }
a.on .pill { background: var(--accent-soft); }
@media (max-width: 820px) { .tabbar { display: flex; } }
</style>
