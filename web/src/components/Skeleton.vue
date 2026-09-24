<script setup>
defineProps({ w: { type: String, default: '100%' }, h: { type: String, default: '14px' }, r: { type: String, default: '8px' } })
</script>

<template><span class="sk" :style="{ width: w, height: h, borderRadius: r }" /></template>

<style scoped>
.sk { display: block; background: linear-gradient(90deg, var(--surface-2) 25%, var(--surface-3) 37%, var(--surface-2) 63%); background-size: 200% 100%; animation: shimmer 1.3s linear infinite; }
</style>
