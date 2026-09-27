<script setup>
// options: [{ value, label, count?, icon? }]
defineProps({ modelValue: [String, Number, Boolean], options: { type: Array, required: true }, block: Boolean, size: { type: String, default: 'md' } })
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div class="seg" :class="[size, { block }]" role="tablist">
    <button v-for="o in options" :key="o.value" type="button" role="tab" :aria-selected="modelValue === o.value"
      :class="{ on: modelValue === o.value }" @click="emit('update:modelValue', o.value)">
      <component :is="o.icon" v-if="o.icon" :size="15" />
      {{ o.label }}
      <span v-if="o.count != null" class="cnt num">{{ o.count }}</span>
    </button>
  </div>
</template>

<style scoped>
.seg { display: inline-flex; gap: 2px; padding: 3px; background: var(--surface-3); border-radius: 12px; max-width: 100%; overflow-x: auto; }
.seg.block { display: flex; }
.seg.block button { flex: 1; }
button {
  display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 0; background: none; color: var(--text-2);
  padding: 7px 14px; border-radius: 9px; font-weight: 600; font-size: 14px; white-space: nowrap; transition: .18s var(--ease);
}
.sm button { padding: 5px 11px; font-size: 13px; }
button:hover:not(.on) { color: var(--text); }
button.on { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm), 0 0 0 1px var(--border); }
.cnt { font-size: 11.5px; padding: 0 6px; border-radius: 99px; background: var(--surface-2); color: var(--text-3); }
.on .cnt { background: var(--accent-soft); color: var(--accent); }
/* Điện thoại: thu gọn để các lựa chọn vừa một hàng (không phải cuộn ngang mới thấy lựa chọn cuối) */
@media (max-width: 640px) {
  .seg { scrollbar-width: none; } .seg::-webkit-scrollbar { display: none; }
  button { padding: 7px 10px; gap: 5px; font-size: 13.5px; }
  .sm button { padding: 5px 8px; gap: 4px; font-size: 13px; }
  .cnt { font-size: 11px; padding: 0 5px; }
}
</style>
