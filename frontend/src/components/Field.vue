<script setup>
import { CircleAlert } from 'lucide-vue-next'
import InfoTip from './InfoTip.vue'

defineProps({ label: String, hint: String, tip: String, error: String })
</script>

<template>
  <div class="field" :class="{ invalid: !!error }">
    <label v-if="label"><span>{{ label }}</span><InfoTip v-if="tip" :tip="tip" /><slot name="aside" /></label>
    <slot />
    <p v-if="error" class="err" role="alert"><CircleAlert :size="14" /><span>{{ error }}</span></p>
    <p v-else-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { margin-bottom: 16px; min-width: 0; }
label { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 13.5px; margin-bottom: 7px; }
.hint { color: var(--text-3); font-size: 13px; margin-top: 6px; }
.err { display: flex; gap: 6px; align-items: flex-start; color: var(--danger); font-size: 13px; margin-top: 6px; line-height: 1.45; animation: nudge .3s var(--ease); }
.err svg { flex: none; margin-top: 2px; }
.invalid :deep(.input:not(.pick .input)), .invalid :deep(.pick) { border-color: var(--danger); box-shadow: 0 0 0 3px var(--danger-soft); }
.invalid :deep(.day:not(.on)) { border-color: var(--danger); }
@keyframes nudge { from { opacity: 0; transform: translateX(-4px); } }
</style>
