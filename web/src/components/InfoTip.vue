<script setup>
import { ref, reactive, computed, onBeforeUnmount } from 'vue'
import { CircleHelp, ArrowRight } from 'lucide-vue-next'
import { TIPS } from '../lib/help'

// Dấu ? giải thích ngắn. Dùng `tip="cpa"` (khoá trong TIPS) hoặc `text="…"` + `topic="rules"`.
const props = defineProps({ tip: String, text: String, topic: String })
const info = computed(() => (props.tip && TIPS[props.tip]) || { text: props.text || '', topic: props.topic })

const open = ref(false)
const btn = ref(null)
const pos = reactive({ top: 0, left: 0, width: 300, above: false })
let timer = 0

function show() {
  clearTimeout(timer)
  if (open.value || !btn.value) return
  const r = btn.value.getBoundingClientRect()
  const width = Math.min(300, window.innerWidth - 24)
  pos.width = width
  pos.left = Math.min(Math.max(12, r.left + r.width / 2 - width / 2), window.innerWidth - width - 12)
  pos.above = r.bottom + 170 > window.innerHeight && r.top > 170
  pos.top = pos.above ? r.top - 8 : r.bottom + 8
  open.value = true
  window.addEventListener('keydown', onKey)
  window.addEventListener('scroll', close, true)
  window.addEventListener('pointerdown', onOutside, true)
}
function close() {
  open.value = false
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('scroll', close, true)
  window.removeEventListener('pointerdown', onOutside, true)
}
const soon = () => { clearTimeout(timer); timer = setTimeout(close, 180) }
const keep = () => clearTimeout(timer)
const onKey = (e) => { if (e.key === 'Escape') close() }
const onOutside = (e) => { if (!e.target.closest('.tip-pop') && !(btn.value && btn.value.contains(e.target))) close() }
onBeforeUnmount(() => { clearTimeout(timer); close() })
</script>

<template>
  <span class="wrap">
    <button ref="btn" type="button" class="q" aria-label="Xem giải thích" @mouseenter="show" @mouseleave="soon" @focus="show" @blur="soon" @click.prevent="open ? close() : show()">
      <CircleHelp :size="15" />
    </button>
    <Teleport to="body">
      <Transition name="tp">
        <div v-if="open" class="tip-pop" :class="{ above: pos.above }" role="tooltip" :style="{ top: pos.top + 'px', left: pos.left + 'px', width: pos.width + 'px' }" @mouseenter="keep" @mouseleave="soon">
          <p>{{ info.text }}</p>
          <RouterLink v-if="info.topic" :to="'/help/' + info.topic" @click="close">Xem hướng dẫn chi tiết <ArrowRight :size="13" /></RouterLink>
        </div>
      </Transition>
    </Teleport>
  </span>
</template>

<style scoped>
.wrap { display: inline-flex; vertical-align: middle; }
.q { display: inline-grid; place-items: center; width: 22px; height: 22px; padding: 0; border: 0; border-radius: 50%; background: none; color: var(--text-3); transition: .15s; }
.q:hover, .q:focus-visible { color: var(--accent); background: var(--accent-soft); }
</style>

<style>
.tip-pop {
  position: fixed; z-index: 250; padding: 12px 14px; border-radius: 14px; font-size: 13.5px; line-height: 1.5; font-weight: 450; letter-spacing: 0; text-transform: none;
  background: var(--surface); color: var(--text); border: 1px solid var(--border-strong); box-shadow: var(--shadow-lg);
}
.tip-pop.above { transform: translateY(-100%); }
.tip-pop p { margin: 0; color: var(--text-2); }
.tip-pop a { display: inline-flex; align-items: center; gap: 4px; margin-top: 8px; font-weight: 600; font-size: 13px; color: var(--accent); text-decoration: none; }
.tip-pop a:hover { text-decoration: underline; }
.tp-enter-active, .tp-leave-active { transition: opacity .15s; }
.tp-enter-from, .tp-leave-to { opacity: 0; }
</style>
