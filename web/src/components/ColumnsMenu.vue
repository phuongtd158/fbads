<script setup>
// Chọn cột hiển thị trong bảng (giống mục "Cột" của Ads Manager) và kéo tay cầm ⋮⋮ để đổi thứ tự.
// v-model = mảng key cột theo đúng thứ tự hiển thị; luôn giữ ít nhất 1 cột.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { Columns3, RotateCcw, MoveHorizontal, GripVertical, ArrowDownUp } from 'lucide-vue-next'
import Popover from './Popover.vue'
import { COLUMNS, COLUMN_KEYS, DEFAULT_COLUMNS, colOf, toggleKey, moveKey, canonicalOrder, isCanonical } from '../lib/overviewColumns'

// Bản sao cục bộ ghi ngay khi bấm (props chỉ đổi sau khi cha vẽ lại), nên bấm liên tiếp nhiều ô không bị mất lần nào
const props = defineProps({ modelValue: { type: Array, required: true }, hasWidths: Boolean })
const emit = defineEmits(['update:modelValue', 'reset-widths'])
const local = ref([...props.modelValue])
watch(() => props.modelValue, (v) => { local.value = [...v] })
const open = ref(false)
const on = computed(() => new Set(local.value))
const isDefault = computed(() => local.value.length === DEFAULT_COLUMNS.length && DEFAULT_COLUMNS.every((k, i) => local.value[i] === k))
const commit = (list) => { local.value = list; emit('update:modelValue', list) }

const toggle = (key) => commit(toggleKey(local.value, key))
const reset = () => commit([...DEFAULT_COLUMNS])
const resetOrder = () => commit(canonicalOrder(local.value, COLUMN_KEYS))
const ordered = computed(() => isCanonical(local.value, COLUMN_KEYS))
// Bảng luôn cần ít nhất 1 cột: đang bật hết mà bỏ tích "Tất cả" thì về các cột mặc định
const allOn = computed(() => local.value.length === COLUMNS.length)
const toggleAll = () => commit(allOn.value ? [...DEFAULT_COLUMNS] : [...local.value, ...COLUMN_KEYS.filter((k) => !on.value.has(k))])

// ----- Kéo thả thứ tự: cột đang bật ở trên (đúng thứ tự trong bảng), cột tắt ở dưới -----
const drag = ref(null) // { key, to } khi đang kéo
const shownOn = computed(() => (drag.value ? moveKey(local.value, drag.value.key, drag.value.to) : local.value).map(colOf))
const shownOff = computed(() => COLUMNS.filter((c) => !on.value.has(c.key)))
const list = ref(null)
let ctx = null
function startDrag(key, ev) {
  if (local.value.length < 2 || (ev.button != null && ev.button !== 0)) return
  ev.preventDefault()
  const el = list.value.$el
  const box = el.closest('.pop-panel') || el
  // chụp vị trí giữa các dòng khác lúc bắt đầu (tính theo nội dung đã cuộn, để cuộn trong lúc kéo vẫn đúng)
  const mids = [...el.querySelectorAll('[data-k]')].filter((el) => el.dataset.k !== key)
    .map((el) => { const r = el.getBoundingClientRect(); return r.top + r.height / 2 + box.scrollTop })
  ctx = { key, box, mids, y: ev.clientY, timer: null }
  drag.value = { key, to: local.value.indexOf(key) }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', endDrag)
  window.addEventListener('pointercancel', cancelDrag)
  ctx.timer = setInterval(autoScroll, 30)
}
function onMove(ev) {
  ctx.y = ev.clientY
  place()
}
function place() {
  const y = ctx.y + ctx.box.scrollTop
  drag.value = { key: ctx.key, to: ctx.mids.filter((m) => m < y).length }
}
// kéo gần mép trên/dưới của menu thì tự cuộn (danh sách dài trên màn hình thấp / điện thoại)
function autoScroll() {
  const r = ctx.box.getBoundingClientRect(), edge = 44
  const d = ctx.y < r.top + edge ? -8 : ctx.y > r.bottom - edge ? 8 : 0
  if (d) { ctx.box.scrollTop += d; place() }
}
function stop() {
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', endDrag)
  window.removeEventListener('pointercancel', cancelDrag)
  if (ctx) clearInterval(ctx.timer)
  ctx = null
}
function endDrag() {
  const d = drag.value
  stop(); drag.value = null
  if (d) { const next = moveKey(local.value, d.key, d.to); if (next !== local.value) commit(next) }
}
function cancelDrag() { stop(); drag.value = null }
onBeforeUnmount(stop)
// Bàn phím: chọn tay cầm rồi bấm ↑ / ↓ để đổi chỗ
function keyMove(key, dir) {
  const i = local.value.indexOf(key)
  const next = moveKey(local.value, key, i + dir)
  if (next !== local.value) commit(next)
}
</script>

<template>
  <Popover v-model="open" align="right" width="290px" label="Chọn cột hiển thị">
    <template #trigger="{ toggle: tg }">
      <button type="button" class="cbtn" :class="{ on: open }" aria-haspopup="dialog" :aria-expanded="open" @click="tg">
        <Columns3 :size="16" /><span class="lb">Cột</span><em class="n num">{{ local.length }}/{{ COLUMNS.length }}</em>
      </button>
    </template>
    <div class="cm">
      <p class="hd">Hiển thị trong bảng</p>
      <label class="row all">
        <input type="checkbox" :checked="allOn" :indeterminate="!allOn" @change="toggleAll" />
        <span>Tất cả các cột</span><em class="num">{{ local.length }}/{{ COLUMNS.length }}</em>
      </label>
      <p class="sub-hd">Đang hiện <span>kéo ⋮⋮ để đổi thứ tự</span></p>
      <TransitionGroup ref="list" tag="div" name="mv" class="list" :class="{ dragging: drag }">
        <div v-for="c in shownOn" :key="c.key" class="row" :data-k="c.key" :class="{ lock: on.size === 1, held: drag && drag.key === c.key }">
          <button type="button" class="grip" :disabled="local.length < 2" :aria-label="'Kéo để đổi vị trí cột ' + (c.menu || c.label) + ' (hoặc bấm ↑ ↓)'"
            title="Kéo để đổi vị trí (hoặc bấm ↑ ↓)" @pointerdown="startDrag(c.key, $event)"
            @keydown.up.prevent="keyMove(c.key, -1)" @keydown.down.prevent="keyMove(c.key, 1)"><GripVertical :size="16" /></button>
          <label>
            <input type="checkbox" checked :disabled="on.size === 1" @change="toggle(c.key)" />
            <span>{{ c.menu || c.label }}</span>
          </label>
        </div>
      </TransitionGroup>
      <template v-if="shownOff.length">
        <p class="sub-hd">Đang ẩn</p>
        <div v-for="c in shownOff" :key="c.key" class="row">
          <span class="grip ph" />
          <label>
            <input type="checkbox" @change="toggle(c.key)" />
            <span>{{ c.menu || c.label }}</span>
          </label>
        </div>
      </template>
      <button type="button" class="reset" :disabled="isDefault" @click="reset"><RotateCcw :size="14" />Đặt lại mặc định</button>
      <button type="button" class="reset sub" :disabled="ordered" title="Xếp các cột đang hiện về thứ tự ban đầu" @click="resetOrder"><ArrowDownUp :size="14" />Đặt lại thứ tự cột</button>
      <button type="button" class="reset sub" :disabled="!hasWidths" title="Kéo mép phải tiêu đề cột để đổi độ rộng" @click="emit('reset-widths')"><MoveHorizontal :size="14" />Đặt lại độ rộng cột</button>
    </div>
  </Popover>
</template>

<style scoped>
.cbtn {
  display: inline-flex; align-items: center; gap: 8px; min-height: 42px; padding: 6px 13px; border-radius: 12px; cursor: pointer;
  background: var(--surface); border: 1px solid var(--border-strong); color: var(--text-2); font: inherit; font-size: 14px; font-weight: 600; box-shadow: var(--shadow-sm);
  transition: border-color .15s, box-shadow .15s, color .15s;
}
.cbtn:hover, .cbtn.on { border-color: var(--accent); color: var(--accent); }
.cbtn.on { box-shadow: 0 0 0 4px var(--accent-soft); }
.n { font-style: normal; font-size: 11.5px; padding: 1px 7px; border-radius: 99px; background: var(--surface-3); color: var(--text-3); }
.cm { padding: 8px; }
.hd { margin: 4px 8px 6px; font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--text-3); }
.row { display: flex; align-items: center; gap: 11px; padding: 8px 10px; border-radius: 9px; cursor: pointer; font-size: 14px; }
.row:hover { background: var(--surface-2); }
.list .row, .row:has(.grip) { padding: 0 10px 0 2px; gap: 2px; }
.row label { flex: 1; display: flex; align-items: center; gap: 11px; padding: 8px 0; cursor: pointer; min-width: 0; }
.grip { flex: none; display: grid; place-items: center; width: 28px; height: 34px; border: 0; background: none; color: var(--text-3); border-radius: 7px; cursor: grab; touch-action: none; padding: 0; }
.grip:hover:not(:disabled) { color: var(--accent); background: var(--accent-soft); }
.grip:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.grip:disabled { cursor: default; opacity: .4; }
.grip.ph { cursor: default; }
.list.dragging, .list.dragging * { cursor: grabbing !important; user-select: none; }
.list.dragging .row:hover { background: none; }
.row.held { background: var(--accent-soft) !important; box-shadow: inset 0 0 0 1.5px var(--accent); position: relative; z-index: 1; }
.row.held .grip { color: var(--accent); }
.mv-move { transition: transform .15s var(--ease, ease); }
.sub-hd { display: flex; justify-content: space-between; align-items: baseline; margin: 8px 10px 2px; font-size: 11.5px; font-weight: 650; color: var(--text-3); }
.sub-hd span { font-weight: 500; }
.row input { flex: none; accent-color: var(--accent); width: 17px; height: 17px; margin: 0; cursor: pointer; }
.row.all { font-weight: 650; border-bottom: 1px solid var(--border); border-radius: 9px 9px 0 0; margin-bottom: 4px; }
.row.all em { margin-left: auto; font-style: normal; font-size: 12px; color: var(--text-3); }
.row.lock label { opacity: .6; cursor: not-allowed; } .row.lock input { cursor: not-allowed; }
.reset { display: inline-flex; align-items: center; gap: 7px; width: 100%; margin-top: 6px; padding: 9px 10px; border: 0; border-top: 1px solid var(--border); background: none; color: var(--accent); font: inherit; font-size: 13.5px; font-weight: 600; cursor: pointer; border-radius: 0 0 9px 9px; }
.reset:hover:not(:disabled) { background: var(--accent-soft); }
.reset:disabled { color: var(--text-3); cursor: default; }
.reset.sub { margin-top: 0; border-top: 0; }
@media (max-width: 640px) { .cbtn .lb { display: none; } }
</style>
