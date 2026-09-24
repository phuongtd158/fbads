<script setup>
defineProps({ tone: { type: String, default: 'neutral' }, dot: Boolean })
</script>

<template>
  <span class="badge" :class="tone"><i v-if="dot" class="dot" /><slot /></span>
</template>

<style scoped>
.badge { display: inline-flex; align-items: center; gap: 6px; padding: 2px 10px; border-radius: 99px; font-size: 12.5px; font-weight: 600; white-space: nowrap; line-height: 1.6; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 22%, transparent); }
.neutral { background: var(--surface-3); color: var(--text-2); }
.success { background: var(--success-soft); color: var(--success); }
.danger { background: var(--danger-soft); color: var(--danger); }
.warning { background: var(--warning-soft); color: var(--warning); }
.info { background: var(--info-soft); color: var(--info); }
.accent { background: var(--accent-soft); color: var(--accent); }
</style>
