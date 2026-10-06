<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { PAGE_BY_ID } from '../lib/nav'
import { mobileTabs } from '../stores/ui'

const route = useRoute()
// Các mục do người dùng chọn ở Cài đặt → Giao diện (mặc định: Tổng quan, Lịch, Rule, Nhật ký, Cài đặt)
const items = computed(() => mobileTabs.value.map((id) => PAGE_BY_ID[id]).filter(Boolean))
</script>

<template>
  <nav class="tabbar">
    <RouterLink v-for="i in items" :key="i.id" :to="i.to" :class="{ on: route.name === i.id }">
      <span class="pill"><component :is="i.icon" :size="20" /></span>
      <small>{{ i.short }}</small>
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
small { font-size: 11px; white-space: nowrap; }
a.on { color: var(--accent); }
a.on .pill { background: var(--accent-soft); }
@media (max-width: 820px) { .tabbar { display: flex; } }
</style>
