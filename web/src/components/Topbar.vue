<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Search } from 'lucide-vue-next'
import { palette } from '../stores/ui'
import ThemeToggle from './ThemeToggle.vue'

const route = useRoute()
const meta = computed(() => route.meta || {})
</script>

<template>
  <header class="top">
    <div class="ttl">
      <h1>{{ meta.title }}</h1>
      <p class="muted">{{ meta.sub }}</p>
    </div>
    <div class="right">
      <div id="page-actions" class="actions" />
      <button class="qs" aria-label="Tìm nhanh" @click="palette = true"><Search :size="18" /></button>
      <ThemeToggle />
    </div>
  </header>
</template>

<style scoped>
.top { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 24px; }
h1 { font-size: 30px; letter-spacing: -.03em; line-height: 1.15; font-weight: 700; }
.ttl p { margin-top: 5px; font-size: 15px; }
.right { display: flex; align-items: center; gap: 10px; }
.actions { display: flex; gap: 10px; align-items: center; }
.qs { display: none; width: 40px; height: 40px; border-radius: 13px; border: 1px solid var(--border-strong); background: var(--surface); color: var(--text-2); place-items: center; box-shadow: var(--shadow-sm); }
@media (max-width: 820px) { h1 { font-size: 24px; } .qs { display: grid; } .top { align-items: center; } .ttl p { display: none; } }
</style>
