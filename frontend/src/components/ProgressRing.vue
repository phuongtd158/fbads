<script setup>
import { computed } from 'vue'
const props = defineProps({ value: { type: Number, default: 0 }, size: { type: Number, default: 132 }, stroke: { type: Number, default: 12 } })
const r = computed(() => (props.size - props.stroke) / 2)
const c = computed(() => 2 * Math.PI * r.value)
const off = computed(() => c.value * (1 - Math.min(100, Math.max(0, props.value)) / 100))
</script>

<template>
  <div class="ring" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <defs>
        <linearGradient id="ringg" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="var(--accent)" /><stop offset="100%" stop-color="var(--accent-2)" /></linearGradient>
      </defs>
      <circle :cx="size / 2" :cy="size / 2" :r="r" fill="none" stroke="var(--surface-3)" :stroke-width="stroke" />
      <circle :cx="size / 2" :cy="size / 2" :r="r" fill="none" stroke="url(#ringg)" :stroke-width="stroke" stroke-linecap="round"
        :stroke-dasharray="c" :stroke-dashoffset="off" :transform="`rotate(-90 ${size / 2} ${size / 2})`" class="arc" />
    </svg>
    <div class="mid"><slot /></div>
  </div>
</template>

<style scoped>
.ring { position: relative; flex: none; }
.arc { transition: stroke-dashoffset .9s var(--ease); }
.mid { position: absolute; inset: 0; display: grid; place-items: center; text-align: center; }
</style>
