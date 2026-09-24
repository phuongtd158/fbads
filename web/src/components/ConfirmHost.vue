<script setup>
import { watch, onBeforeUnmount } from 'vue'
import { confirmState as c, settleConfirm } from '../stores/ui'
import { AlertTriangle, HelpCircle } from 'lucide-vue-next'
import Btn from './Btn.vue'

const onKey = (e) => {
  if (e.key === 'Escape') { e.stopImmediatePropagation(); settleConfirm(false) }
  if (e.key === 'Enter') { e.stopImmediatePropagation(); settleConfirm(true) }
}
watch(() => c.open, (o) => { if (o) window.addEventListener('keydown', onKey, true); else window.removeEventListener('keydown', onKey, true) })
onBeforeUnmount(() => window.removeEventListener('keydown', onKey, true))
</script>

<template>
  <Teleport to="body">
    <Transition name="cf">
      <div v-if="c.open" class="ov" @mousedown.self="settleConfirm(false)">
        <div class="box" role="alertdialog" aria-modal="true">
          <div class="ic" :class="{ danger: c.danger }"><component :is="c.danger ? AlertTriangle : HelpCircle" :size="22" /></div>
          <h3>{{ c.title }}</h3>
          <p class="muted">{{ c.message }}</p>
          <div class="btns">
            <Btn @click="settleConfirm(false)">Huỷ</Btn>
            <Btn :variant="c.danger ? 'danger' : 'primary'" @click="settleConfirm(true)">{{ c.ok }}</Btn>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ov { position: fixed; inset: 0; z-index: 120; display: grid; place-items: center; padding: 20px; background: rgba(8, 10, 20, .55); backdrop-filter: blur(6px); }
.box { width: min(420px, 100%); background: var(--surface); border: 1px solid var(--border-strong); border-radius: var(--r-xl); padding: 26px; box-shadow: var(--shadow-lg); text-align: center; }
.ic { width: 52px; height: 52px; border-radius: 18px; display: grid; place-items: center; margin: 0 auto 14px; background: var(--accent-soft); color: var(--accent); }
.ic.danger { background: var(--danger-soft); color: var(--danger); }
h3 { font-size: 18px; letter-spacing: -.02em; margin-bottom: 6px; }
p { font-size: 14.5px; }
.btns { display: flex; gap: 10px; justify-content: center; margin-top: 22px; }
.btns > * { flex: 1; }
.cf-enter-active, .cf-leave-active { transition: opacity .18s; }
.cf-enter-active .box, .cf-leave-active .box { transition: transform .28s var(--ease); }
.cf-enter-from, .cf-leave-to { opacity: 0; }
.cf-enter-from .box, .cf-leave-to .box { transform: scale(.94); }
</style>
