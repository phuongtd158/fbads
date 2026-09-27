<script setup>
import { CheckCircle2, AlertCircle } from 'lucide-vue-next'
import { toasts } from '../stores/ui'
</script>

<template>
  <Teleport to="body">
    <TransitionGroup name="t" tag="div" class="host" aria-live="polite">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind">
        <component :is="t.kind === 'error' ? AlertCircle : CheckCircle2" :size="18" />
        <span>{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<style scoped>
.host { position: fixed; z-index: 200; bottom: 24px; left: 50%; transform: translateX(-50%); display: flex; flex-direction: column; gap: 10px; align-items: center; pointer-events: none; width: max-content; max-width: 92vw; }
.toast {
  display: flex; gap: 10px; align-items: center; padding: 12px 18px; border-radius: 14px; font-weight: 500; font-size: 14.5px;
  background: color-mix(in srgb, var(--text) 92%, var(--bg)); color: var(--bg); box-shadow: var(--shadow-lg);
}
.toast svg { color: #34d399; flex: none; }
.toast.error svg { color: #fb7185; }
.t-enter-active, .t-leave-active { transition: all .35s var(--ease); }
.t-enter-from { opacity: 0; transform: translateY(16px) scale(.95); }
.t-leave-to { opacity: 0; transform: translateY(-8px) scale(.95); }
.t-move { transition: transform .3s var(--ease); }
@media (max-width: 820px) { .host { bottom: 92px; } }
</style>
