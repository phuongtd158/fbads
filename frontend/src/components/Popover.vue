<script setup>
// Khung nổi dưới một nút bấm (v-model = đang mở). Nằm trên cùng trang (Teleport) nên không bị thẻ cha cắt mất.
//  slot "trigger"  : nút mở, nhận { open, toggle }
//  slot mặc định   : nội dung khung, nhận { close }
//  align           : 'left' | 'right' — căn theo mép trái/phải của nút
//  sheet           : điện thoại (≤ 640px) hiện dạng ngăn kéo từ dưới lên
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'

const props = defineProps({ align: { type: String, default: 'left' }, width: { type: String, default: '' }, label: { type: String, default: '' }, sheet: { type: Boolean, default: true } })
const open = defineModel({ type: Boolean, default: false })
const root = ref(null)
const panel = ref(null)
const mobile = ref(false)
const pos = ref({ top: 0, left: 0, maxH: 480 })
let ro = null

function measure() {
  mobile.value = props.sheet && window.matchMedia('(max-width: 640px)').matches
  const trig = root.value, el = panel.value
  if (mobile.value || !trig || !el) return
  const r = trig.getBoundingClientRect(), pw = el.offsetWidth, ph = el.scrollHeight, gap = 8
  const left = Math.max(gap, Math.min(props.align === 'right' ? r.right - pw : r.left, innerWidth - pw - gap))
  let top = r.bottom + gap, maxH = innerHeight - top - gap
  // không đủ chỗ phía dưới mà phía trên rộng hơn → mở lên trên
  if (ph > maxH && r.top - 2 * gap > maxH) { maxH = r.top - 2 * gap; top = r.top - gap - Math.min(ph, maxH) }
  pos.value = { top, left, maxH: Math.max(180, maxH) }
}
const onDown = (e) => { if (open.value && root.value && !root.value.contains(e.target) && !(panel.value && panel.value.contains(e.target))) open.value = false }
const onKey = (e) => {
  if (e.key !== 'Escape' || !open.value) return
  open.value = false
  const b = root.value && root.value.querySelector('button, [tabindex]')
  if (b) b.focus()
}

watch(open, async (v) => {
  if (v) {
    document.addEventListener('pointerdown', onDown, true)
    document.addEventListener('keydown', onKey)
    addEventListener('resize', measure); addEventListener('scroll', measure, true)
    mobile.value = props.sheet && window.matchMedia('(max-width: 640px)').matches
    await nextTick(); measure()
    // nội dung đổi cỡ sau khi mở (vd lịch tải muộn) → đặt lại vị trí
    if (panel.value && 'ResizeObserver' in window) { ro = new ResizeObserver(measure); ro.observe(panel.value) }
  } else teardown()
})
function teardown() {
  document.removeEventListener('pointerdown', onDown, true); document.removeEventListener('keydown', onKey)
  removeEventListener('resize', measure); removeEventListener('scroll', measure, true)
  if (ro) { ro.disconnect(); ro = null }
}
onBeforeUnmount(teardown)
</script>

<template>
  <span ref="root" class="pop-root">
    <slot name="trigger" :open="open" :toggle="() => (open = !open)" />
    <Teleport to="body">
      <div v-if="open && mobile" class="pop-back" @click="open = false" />
      <Transition :name="mobile ? 'sheet' : 'pop'">
        <div v-if="open" ref="panel" class="pop-panel" :class="{ sheet: mobile }" role="dialog" :aria-label="label || undefined"
          :style="mobile ? null : { top: pos.top + 'px', left: pos.left + 'px', maxHeight: pos.maxH + 'px', width: width || undefined }">
          <span v-if="mobile" class="grab" aria-hidden="true" />
          <slot :close="() => (open = false)" />
        </div>
      </Transition>
    </Teleport>
  </span>
</template>

<style scoped>
.pop-root { display: inline-flex; }
.pop-panel {
  position: fixed; z-index: 90; overflow: auto; max-width: calc(100vw - 16px);
  background: var(--surface); border: 1px solid var(--border-strong); border-radius: 16px; box-shadow: var(--shadow-lg);
}
.pop-panel.sheet { inset: auto 0 0 0; max-width: none; width: auto; max-height: 88vh; border-radius: 22px 22px 0 0; border-bottom: 0; padding-bottom: env(safe-area-inset-bottom); }
.pop-back { position: fixed; inset: 0; z-index: 89; background: rgba(6, 8, 18, .5); }
.grab { display: block; width: 38px; height: 4px; border-radius: 9px; background: var(--border-strong); margin: 9px auto 2px; }
.pop-enter-active, .pop-leave-active { transition: opacity .14s ease, transform .16s var(--ease); }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(-5px) scale(.985); }
.sheet-enter-active, .sheet-leave-active { transition: transform .22s var(--ease); }
.sheet-enter-from, .sheet-leave-to { transform: translateY(100%); }
</style>
