<script setup>
defineProps({ modelValue: Boolean, loading: Boolean, disabled: Boolean, label: String })
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <button type="button" role="switch" class="sw" :class="{ on: modelValue, loading }" :aria-checked="modelValue" :aria-label="label"
    :disabled="disabled || loading" @click="emit('update:modelValue', !modelValue)">
    <i />
  </button>
</template>

<style scoped>
.sw {
  position: relative; width: 44px; height: 26px; border-radius: 99px; border: 0; padding: 0; flex: none;
  background: var(--surface-3); box-shadow: inset 0 0 0 1px var(--border-strong); transition: background .25s var(--ease), box-shadow .25s;
}
.sw i {
  position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .3); transition: transform .3s cubic-bezier(.34, 1.56, .64, 1);
}
.sw.on { background: var(--success); box-shadow: 0 0 0 4px var(--success-soft); }
.sw.on i { transform: translateX(18px); }
.sw:active:not(:disabled) i { width: 24px; }
.sw.on:active:not(:disabled) i { transform: translateX(14px); }
.sw:disabled { cursor: not-allowed; opacity: .55; }
.sw.loading { animation: pulse .7s ease-in-out infinite alternate; cursor: progress; }
@keyframes pulse { to { opacity: .4; } }
</style>
