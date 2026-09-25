<script setup>
// Lịch chọn khoảng ngày (thư viện @vuepic/vue-datepicker). Tách riêng để chỉ tải thư viện khi mở bộ chọn ngày lần đầu.
import { computed } from 'vue'
import { VueDatePicker } from '@vuepic/vue-datepicker'
import '@vuepic/vue-datepicker/dist/main.css'
import { vi } from 'date-fns/locale/vi'
import { resolvedTheme } from '../stores/ui'

const model = defineModel({ type: Array, default: null }) // [Date, Date] hoặc null
const props = defineProps({ min: { type: Date, default: undefined }, max: { type: Date, default: undefined }, months: { type: Number, default: 2 } })
const emit = defineEmits(['start']) // đã bấm ngày bắt đầu, đang chờ ngày kết thúc
const dark = computed(() => resolvedTheme.value === 'dark')
// bảng chọn năm chỉ liệt kê các năm chọn được (mặc định của thư viện là 1900–2100)
const yearRange = computed(() => (props.min && props.max ? [props.min.getFullYear(), props.max.getFullYear()] : [1900, 2100]))
</script>

<template>
  <div class="calpane">
    <VueDatePicker
      v-model="model" inline auto-apply hide-offset-dates :dark="dark" :locale="vi" :week-start="1"
      :range="{ partialRange: false }" :multi-calendars="months > 1 ? months : false" :min-date="min" :max-date="max"
      :year-range="yearRange" prevent-min-max-navigation :time-config="{ enableTimePicker: false }" :config="{ noSwipe: false }" @range-start="(d) => emit('start', d)"
    />
  </div>
</template>

<style>
/* Màu của lịch lấy từ bộ màu của ứng dụng để khớp cả giao diện sáng lẫn tối */
.calpane .dp--theme-light, .calpane .dp--theme-dark {
  /* phải là màu ĐẶC: bảng chọn tháng/năm (.dp--overlay) dùng màu này để che lịch phía sau; khung nổi cũng là --surface nên nhìn như cũ */
  --dp-background-color: var(--surface);
  --dp-text-color: var(--text);
  --dp-hover-color: var(--surface-3);
  --dp-hover-text-color: var(--text);
  --dp-hover-icon-color: var(--text-2);
  --dp-primary-color: var(--accent);
  --dp-primary-disabled-color: color-mix(in srgb, var(--accent) 40%, var(--surface));
  --dp-primary-text-color: var(--on-accent);
  --dp-secondary-color: var(--text-3);
  --dp-border-color: transparent;
  --dp-menu-border-color: transparent;
  --dp-border-color-hover: var(--accent);
  --dp-border-color-focus: var(--accent);
  --dp-disabled-color: transparent;
  --dp-disabled-color-text: color-mix(in srgb, var(--text-3) 55%, transparent);
  --dp-scroll-bar-background: var(--surface-2);
  --dp-scroll-bar-color: var(--border-strong);
  --dp-success-color: var(--success);
  --dp-icon-color: var(--text-3);
  --dp-danger-color: var(--danger);
  --dp-highlight-color: var(--accent-soft);
  --dp-range-between-dates-background-color: var(--accent-soft);
  --dp-range-between-dates-text-color: var(--text);
  --dp-range-between-border-color: transparent;
  --dp-font-family: var(--font);
  --dp-font-size: 14px;
  --dp-cell-size: 38px;
  --dp-cell-border-radius: 10px;
  --dp-border-radius: 12px;
  --dp-menu-padding: 6px 4px;
  --dp-tooltip-color: var(--surface-3);
}
.calpane .dp--menu { border: 0; box-shadow: none; }
.calpane .dp--calendar-header-separator { display: none; }
</style>
