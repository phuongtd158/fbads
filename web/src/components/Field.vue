<script setup>
defineProps({ label: String, hint: String })
</script>

<template>
  <div class="field">
    <label v-if="label"><span>{{ label }}</span><slot name="aside" /></label>
    <slot />
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { margin-bottom: 16px; min-width: 0; }
label { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 13.5px; margin-bottom: 7px; }
.hint { color: var(--text-3); font-size: 13px; margin-top: 6px; }
</style>
