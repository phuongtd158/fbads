<script setup>
// Ô nhập số tiền: chỉ nhận chữ số, gõ đến đâu tự thêm dấu chấm hàng nghìn đến đó (1000000 → 1.000.000).
// v-model nhận/trả số nguyên; để trống → ''. negative: cho gõ dấu - ở đầu (ô cộng/trừ số tiền); chỉ có "-" thì trả '-' để phần kiểm tra báo lỗi.
import { ref, watch, computed, useAttrs, onMounted } from 'vue'
import { parseMoney, formatMoneyTyping } from '../lib/money'

defineOptions({ inheritAttrs: false })
const props = defineProps({ modelValue: { type: [Number, String], default: '' }, placeholder: String, negative: Boolean })
const emit = defineEmits(['update:modelValue', 'input'])
// class/style (độ rộng) đặt cho khung ngoài, các thuộc tính còn lại (aria-label, @keydown…) cho ô nhập
const attrs = useAttrs()
const inputAttrs = computed(() => { const { class: _c, style: _s, ...rest } = attrs; return rest })

const fmtText = (s, caret) => formatMoneyTyping(s, caret, { negative: props.negative })
// Giá trị cũ có thể là chuỗi kiểu "100k" (bản nháp trước đây) → đọc ra số rồi mới hiện
const show = (v) => {
  if (v === '' || v == null) return ''
  const n = parseMoney(v)
  return fmtText(Number.isFinite(n) ? String(n) : String(v)).text
}
const valueOf = (t) => (t === '' ? '' : t === '-' ? '-' : Number(t.replace(/\./g, '')))

const el = ref(null)
const text = ref(show(props.modelValue))
watch(() => props.modelValue, (v) => { if (valueOf(text.value) !== v) text.value = show(v) })
// Giá trị truyền vào khác số đang hiện (chuỗi "100k" cũ, "-20" khi đổi từ ô % sang ô không cho số âm) → trả lại đúng số đang hiện
onMounted(() => { if (props.modelValue !== '' && props.modelValue != null && valueOf(text.value) !== props.modelValue) emit('update:modelValue', valueOf(text.value)) })

function onInput(e) {
  const t = e.target
  const r = fmtText(t.value, t.selectionStart)
  text.value = r.text
  t.value = r.text
  if (document.activeElement === t) t.setSelectionRange(r.caret, r.caret)
  emit('update:modelValue', valueOf(r.text))
  emit('input', e)
}
// Xoá trúng dấu chấm thì xoá luôn chữ số bên cạnh (không thì dấu chấm tự hiện lại, trông như phím không ăn)
function onKeydown(e) {
  const t = e.target
  if (t.selectionStart !== t.selectionEnd) return
  const i = t.selectionStart
  if (e.key === 'Backspace' && t.value[i - 1] === '.') t.setSelectionRange(i - 1, i - 1)
  else if (e.key === 'Delete' && t.value[i] === '.') t.setSelectionRange(i + 1, i + 1)
}

defineExpose({ focus: () => el.value && el.value.focus(), select: () => el.value && el.value.select() })
</script>

<template>
  <div class="money" :class="attrs.class" :style="attrs.style">
    <input ref="el" :value="text" class="input" :inputmode="negative ? 'text' : 'numeric'" autocomplete="off" :placeholder="placeholder" v-bind="inputAttrs" @input="onInput" @keydown="onKeydown" />
  </div>
</template>

<style scoped>
.money { position: relative; min-width: 0; }
.money .input { width: 100%; }
</style>
