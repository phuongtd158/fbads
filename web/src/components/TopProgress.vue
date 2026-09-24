<script setup>
import { ref, watch } from 'vue'
import { net } from '../lib/api'

const on = ref(false)
let timer = 0
// chỉ hiện khi gọi API lâu hơn 150ms để khỏi nháy
watch(() => net.pending, (n) => {
  clearTimeout(timer)
  if (n > 0) timer = setTimeout(() => (on.value = true), 150)
  else on.value = false
})
</script>

<template><div class="bar" :class="{ on }" /></template>

<style scoped>
.bar { position: fixed; top: 0; left: 0; right: 0; height: 3px; z-index: 300; opacity: 0; transition: opacity .25s; pointer-events: none;
  background: linear-gradient(90deg, transparent, var(--accent), var(--accent-2), transparent) no-repeat; background-size: 40% 100%; }
.bar.on { opacity: 1; animation: run 1.05s linear infinite; }
@keyframes run { from { background-position: -50% 0; } to { background-position: 150% 0; } }
</style>
