<script setup>
// Ô nhập số tiền: gõ được 150000, 150.000, 150k, 1,5tr, 2 triệu. Khi rời ô thì hiện dạng 150.000.
// v-model nhận/trả số; để trống → ''; gõ sai → trả nguyên chuỗi để phần kiểm tra báo lỗi.
import { ref, watch, computed, useAttrs } from 'vue'
import { parseMoney } from '../lib/money'
import { fmt } from '../lib/format'

defineOptions({ inheritAttrs: false })
const props = defineProps({ modelValue: { type: [Number, String], default: '' }, placeholder: String })
const emit = defineEmits(['update:modelValue', 'input'])
// class/style (độ rộng) đặt cho khung ngoài, các thuộc tính còn lại (aria-label…) cho ô nhập
const attrs = useAttrs()
const inputAttrs = computed(() => { const { class: _c, style: _s, ...rest } = attrs; return rest })

const show = (v) => (v === '' || v == null ? '' : typeof v === 'number' || /^\d+(\.\d+)?$/.test(String(v)) ? fmt(Number(v)) : String(v))
const text = ref(show(props.modelValue))
const focused = ref(false)
watch(() => props.modelValue, (v) => { if (!focused.value || parseMoney(text.value) !== v) text.value = show(v) })

const parsed = computed(() => parseMoney(text.value))
// Gõ kiểu rút gọn (150k, 1,5tr) thì hiện số đầy đủ ngay bên cạnh để chắc chắn đúng ý
const echo = computed(() => (focused.value && typeof parsed.value === 'number' && !Number.isNaN(parsed.value) && /[a-zà-ỹ]/i.test(text.value) ? `= ${fmt(parsed.value)}` : ''))

function onInput(e) {
  text.value = e.target.value
  const v = parseMoney(text.value)
  emit('update:modelValue', Number.isNaN(v) ? text.value : v)
  emit('input', e)
}
function onBlur() {
  focused.value = false
  if (typeof parsed.value === 'number' && !Number.isNaN(parsed.value)) text.value = show(parsed.value)
}
</script>

<template>
  <div class="money" :class="attrs.class" :style="attrs.style">
    <input :value="text" class="input" inputmode="decimal" autocomplete="off" :placeholder="placeholder" v-bind="inputAttrs" @input="onInput" @focus="focused = true" @blur="onBlur" />
    <span v-if="echo" class="echo">{{ echo }}</span>
  </div>
</template>

<style scoped>
.money { position: relative; min-width: 0; }
.money .input { width: 100%; }
.echo { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); white-space: nowrap; border-radius: 6px; font-size: 12px; font-weight: 600; color: var(--accent); background: var(--surface); padding: 0 4px; pointer-events: none; }
</style>
