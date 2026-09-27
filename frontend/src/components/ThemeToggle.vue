<script setup>
import { Sun, Moon } from 'lucide-vue-next'
import { resolvedTheme, toggleTheme } from '../stores/ui'
</script>

<template>
  <button class="tt" :title="resolvedTheme === 'dark' ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối'" aria-label="Đổi giao diện sáng/tối" @click="toggleTheme">
    <Sun class="sun" :size="18" /><Moon class="moon" :size="18" />
  </button>
</template>

<style scoped>
.tt { position: relative; width: 40px; height: 40px; border-radius: 13px; border: 1px solid var(--border-strong); background: var(--surface); color: var(--text-2); display: grid; place-items: center; box-shadow: var(--shadow-sm); overflow: hidden; transition: .2s var(--ease); }
.tt:hover { color: var(--accent); border-color: var(--accent); transform: translateY(-1px); }
.tt svg { position: absolute; transition: transform .5s var(--ease), opacity .3s; }
.sun { transform: rotate(90deg) scale(0); opacity: 0; }
.moon { transform: rotate(0) scale(1); opacity: 1; }
[data-theme='light'] .sun { transform: rotate(0) scale(1); opacity: 1; color: var(--warning); }
[data-theme='light'] .moon { transform: rotate(-90deg) scale(0); opacity: 0; }
</style>
