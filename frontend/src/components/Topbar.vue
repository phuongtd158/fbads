<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Search, CircleHelp } from 'lucide-vue-next'
import { palette } from '../stores/ui'
import ThemeToggle from './ThemeToggle.vue'

const route = useRoute()
const meta = computed(() => route.meta || {})
</script>

<template>
  <!-- Máy tính: [tiêu đề | nút của trang | giao diện]. Điện thoại: [tiêu đề | tìm, hướng dẫn, giao diện] rồi hàng nút của trang (tự xuống dòng) -->
  <header class="top">
    <div class="ttl">
      <h1>{{ meta.title }}</h1>
      <p class="muted">{{ meta.sub }}</p>
    </div>
    <div id="page-actions" class="actions" />
    <div class="tools">
      <button class="qs" aria-label="Tìm nhanh" @click="palette = true"><Search :size="18" /></button>
      <RouterLink to="/help" class="qs" title="Hướng dẫn sử dụng" aria-label="Hướng dẫn sử dụng"><CircleHelp :size="18" /></RouterLink>
      <ThemeToggle />
    </div>
  </header>
</template>

<style scoped>
.top { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; grid-template-areas: 'ttl actions tools'; align-items: end; gap: 16px 10px; margin-bottom: 24px; }
.ttl { grid-area: ttl; min-width: 0; }
h1 { font-size: 30px; letter-spacing: -.03em; line-height: 1.15; font-weight: 700; }
.ttl p { margin-top: 5px; font-size: 15px; }
.actions { grid-area: actions; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; justify-content: flex-end; }
.actions:empty { display: none; }
.tools { grid-area: tools; display: flex; align-items: center; gap: 8px; }
.qs { display: none; width: 40px; height: 40px; border-radius: 13px; border: 1px solid var(--border-strong); background: var(--surface); color: var(--text-2); place-items: center; box-shadow: var(--shadow-sm); }
@media (max-width: 820px) {
  .top { grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: 'ttl tools' 'actions actions'; align-items: center; gap: 14px 10px; margin-bottom: 18px; }
  h1 { font-size: 24px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .ttl p { display: none; }
  .qs { display: grid; }
  .actions { justify-content: flex-start; }
  .actions > :deep(*) { flex: 0 1 auto; }
}
</style>
