<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { AlertTriangle, Clock, Eye } from 'lucide-vue-next'
import { state, canEdit } from '../stores/app'
import Btn from './Btn.vue'

const route = useRoute()
// Cảnh báo toàn cục: không ghi được dữ liệu lên nơi lưu trữ, mất kết nối Facebook hoặc token sắp hết hạn
// (hai loại sau không hiện ở trang Cài đặt vì đã có thẻ trạng thái riêng)
const alert = computed(() => {
  const st = state.storage
  if (!canEdit()) return { tone: 'info', icon: Eye, title: 'Bạn đang ở chế độ chỉ xem.', text: `Vai trò của bạn trong “${state.auth.workspace?.name}” không cho sửa. Nhờ chủ workspace đổi vai trò nếu cần.` }
  if (st && st.lastError) return { tone: 'danger', icon: AlertTriangle, title: `Chưa lưu được dữ liệu lên ${st.provider || 'nơi lưu trữ'}.`, text: `${st.lastError}. Tool đang tự thử lại; nếu tool khởi động lại lúc này, thay đổi mới có thể mất.` }
  if (state.settings.mock || !state.conn || route.name === 'settings') return null
  if (!state.conn.ok) return { tone: 'danger', icon: AlertTriangle, title: 'Mất kết nối Facebook.', text: state.conn.error, cta: 'Sửa kết nối' }
  const t = state.conn.token
  if (t && t.daysLeft != null && t.daysLeft <= 7) {
    return { tone: 'warning', icon: Clock, title: 'Token sắp hết hạn', text: t.daysLeft >= 1 ? `còn ${t.daysLeft} ngày. Tạo token mới để tool không bị ngưng.` : 'trong hôm nay. Tạo token mới ngay để tool không bị ngưng.', cta: 'Đổi token' }
  }
  return null
})
</script>

<template>
  <Transition name="al">
    <div v-if="alert" class="al" :class="alert.tone" role="alert">
      <component :is="alert.icon" :size="19" />
      <p><b>{{ alert.title }}</b> {{ alert.text }}</p>
      <RouterLink v-if="alert.cta" to="/settings/connection"><Btn size="sm">{{ alert.cta }}</Btn></RouterLink>
    </div>
  </Transition>
</template>

<style scoped>
.al { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-radius: var(--r-md); margin-bottom: 18px; font-size: 14.5px; }
.al p { flex: 1; }
.danger { background: var(--danger-soft); color: var(--danger); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--danger) 25%, transparent); }
.info { background: var(--surface-3); color: var(--text-2); }
.warning { background: var(--warning-soft); color: var(--warning); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--warning) 25%, transparent); }
.al-enter-active, .al-leave-active { transition: all .3s var(--ease); }
.al-enter-from, .al-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
