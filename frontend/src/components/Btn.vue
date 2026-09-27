<script setup>
import { ref } from 'vue'
import { Loader2 } from 'lucide-vue-next'
import { toastError } from '../stores/ui'

// Truyền `action` (hàm async) để nút tự hiện spinner + tự khoá khi đang chạy.
const props = defineProps({
  variant: { type: String, default: 'secondary' }, // primary | secondary | ghost | danger
  size: { type: String, default: 'md' },           // sm | md | lg
  icon: { type: [Object, Function], default: null },
  loading: Boolean,
  disabled: Boolean,
  block: Boolean,
  action: { type: Function, default: null },
  type: { type: String, default: 'button' },
})
const emit = defineEmits(['click'])
const busy = ref(false)

async function onClick(e) {
  if (!props.action) return emit('click', e)
  if (busy.value) return
  busy.value = true
  try { await props.action(e) } catch (err) { toastError(err) } finally { busy.value = false }
}
</script>

<template>
  <button :type="type" class="btn" :class="[variant, size, { block, busy: busy || loading, only: !$slots.default }]" :disabled="disabled || busy || loading" @click="onClick">
    <Loader2 v-if="busy || loading" class="spin" :size="size === 'sm' ? 14 : 16" />
    <component :is="icon" v-else-if="icon" :size="size === 'sm' ? 14 : 16" />
    <span v-if="$slots.default"><slot /></span>
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px; white-space: nowrap;
  border: 1px solid var(--border-strong); background: var(--surface); color: var(--text);
  border-radius: var(--r-sm); padding: 9px 15px; font-weight: 600; font-size: 14px; line-height: 1.2;
  box-shadow: var(--shadow-sm); transition: transform .15s var(--ease), background .15s, border-color .15s, box-shadow .15s, color .15s;
  user-select: none;
}
.btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); transform: translateY(-1px); }
.btn:active:not(:disabled) { transform: translateY(0) scale(.98); }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.btn.busy:disabled { opacity: .85; cursor: progress; }
.sm { padding: 6px 11px; font-size: 13px; border-radius: 9px; }
.lg { padding: 12px 20px; font-size: 15px; border-radius: 12px; }
.only { padding: 9px; } .only.sm { padding: 6px; }
.block { width: 100%; }
.primary {
  background: var(--accent-grad); color: var(--on-accent); border-color: transparent;
  box-shadow: 0 6px 18px -6px var(--accent-ring), inset 0 1px 0 rgba(255, 255, 255, .25);
}
.primary:hover:not(:disabled) { color: var(--on-accent); border-color: transparent; filter: brightness(1.06); box-shadow: 0 10px 24px -8px var(--accent-ring), inset 0 1px 0 rgba(255, 255, 255, .25); }
.ghost { background: transparent; border-color: transparent; box-shadow: none; color: var(--text-2); }
.ghost:hover:not(:disabled) { background: var(--surface-3); color: var(--text); border-color: transparent; transform: none; }
.danger:hover:not(:disabled) { border-color: var(--danger); color: var(--danger); background: var(--danger-soft); }
.ghost.danger:hover:not(:disabled) { background: var(--danger-soft); color: var(--danger); }
</style>
