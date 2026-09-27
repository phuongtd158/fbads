<script setup>
import { watch, onBeforeUnmount } from 'vue'
import { X } from 'lucide-vue-next'

const props = defineProps({ modelValue: Boolean, title: String, subtitle: String, width: { type: String, default: '640px' } })
const emit = defineEmits(['update:modelValue'])
const close = () => emit('update:modelValue', false)
const onKey = (e) => { if (e.key === 'Escape') close() }

watch(() => props.modelValue, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
  if (open) window.addEventListener('keydown', onKey); else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onBeforeUnmount(() => { document.body.style.overflow = ''; window.removeEventListener('keydown', onKey) })
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="overlay" @mousedown.self="close">
        <div class="sheet" :style="{ maxWidth: width }" role="dialog" aria-modal="true">
          <header>
            <div><h3>{{ title }}</h3><p v-if="subtitle" class="faint">{{ subtitle }}</p></div>
            <button class="x" aria-label="Đóng" @click="close"><X :size="18" /></button>
          </header>
          <div class="body"><slot /></div>
          <footer v-if="$slots.footer"><slot name="footer" /></footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 20px; background: rgba(8, 10, 20, .5); backdrop-filter: blur(6px); }
.sheet {
  width: 100%; max-height: min(88vh, 900px); display: flex; flex-direction: column; overflow: hidden;
  background: var(--surface); border: 1px solid var(--border-strong); border-radius: var(--r-xl); box-shadow: var(--shadow-lg);
}
header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 22px 26px 16px; }
h3 { font-size: 19px; letter-spacing: -.02em; }
header p { font-size: 13.5px; margin-top: 2px; }
.x { border: 0; background: var(--surface-3); color: var(--text-2); width: 32px; height: 32px; border-radius: 10px; display: grid; place-items: center; transition: .15s; }
.x:hover { background: var(--danger-soft); color: var(--danger); }
.body { padding: 4px 26px 22px; overflow: auto; }
footer { padding: 16px 26px; display: flex; justify-content: flex-end; gap: 10px; border-top: 1px solid var(--border); background: var(--surface-2); }
.modal-enter-active, .modal-leave-active { transition: opacity .2s; }
.modal-enter-active .sheet, .modal-leave-active .sheet { transition: transform .3s var(--ease), opacity .2s; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .sheet, .modal-leave-to .sheet { transform: translateY(16px) scale(.97); opacity: 0; }
@media (max-width: 640px) {
  .overlay { align-items: end; padding: 0; }
  .sheet { max-width: none !important; border-radius: var(--r-xl) var(--r-xl) 0 0; max-height: 92vh; }
  .modal-enter-from .sheet, .modal-leave-to .sheet { transform: translateY(100%); }
}
</style>
