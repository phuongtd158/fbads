<script setup>
import { CircleAlert, TriangleAlert, Info } from 'lucide-vue-next'
defineProps({ tone: { type: String, default: 'warning' } }) // warning | danger | info
const icons = { warning: TriangleAlert, danger: CircleAlert, info: Info }
</script>

<template>
  <div class="co" :class="tone" :role="tone === 'danger' ? 'alert' : 'note'">
    <component :is="icons[tone]" :size="17" />
    <div><slot /></div>
  </div>
</template>

<style scoped>
.co { display: flex; gap: 10px; padding: 11px 14px; border-radius: 12px; margin: 0 0 12px; font-size: 13.5px; line-height: 1.55; }
.co svg { flex: none; margin-top: 2px; }
.co > div { flex: 1; min-width: 0; color: var(--text); }
.co :deep(p) { margin: 0; } .co :deep(p + p) { margin-top: 4px; }
.warning { background: var(--warning-soft); color: var(--warning); }
.danger { background: var(--danger-soft); color: var(--danger); }
.info { background: var(--info-soft); color: var(--info); }
</style>
