<script setup>
import { ref, watch, onMounted } from 'vue'
import { fmt, fmtDec } from '../lib/format'

const props = defineProps({ value: { type: Number, default: 0 }, decimals: { type: Number, default: 0 } })
const shown = ref(0)
let raf = 0

function run(to) {
  cancelAnimationFrame(raf)
  const from = shown.value, t0 = performance.now(), dur = 700
  const step = (t) => {
    const p = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - p, 3)
    shown.value = from + (to - from) * e
    if (p < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}
onMounted(() => run(props.value))
watch(() => props.value, (v) => run(v))
</script>

<template><span class="num">{{ decimals ? fmtDec(shown, decimals) : fmt(shown) }}</span></template>
