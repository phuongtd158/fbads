<script setup>
import { computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { state, bootstrap, loadObjs, checkConn } from './stores/app'
import { startLive, stopLive, onLive } from './lib/live'
import { palette } from './stores/ui'
import Sidebar from './components/Sidebar.vue'
import MobileTabbar from './components/MobileTabbar.vue'
import Topbar from './components/Topbar.vue'
import AlertBanner from './components/AlertBanner.vue'
import CommandPalette from './components/CommandPalette.vue'
import ConfirmHost from './components/ConfirmHost.vue'
import ToastHost from './components/ToastHost.vue'
import TopProgress from './components/TopProgress.vue'
import LoginView from './views/LoginView.vue'
import Skeleton from './components/Skeleton.vue'

const route = useRoute()
const showLogin = computed(() => state.ready && state.auth.required && !state.auth.authed)

// Phím tắt: Ctrl/⌘+K mở tìm nhanh
function onKey(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); if (state.auth.authed) palette.value = !palette.value }
}
// Realtime: đăng nhập xong thì nối WebSocket. Camp vừa đổi (bởi lịch/rule, người khác, tab khác) thì tải lại
// bảng Tổng quan sau 2 giây (gom nhiều thay đổi liền nhau thành 1 lần tải); đang sửa ô nào thì để lượt sau.
watch(() => state.ready && state.auth.authed, (on) => (on ? startLive() : stopLive()), { immediate: true })
let objTimer = 0
const offObjects = onLive('objects', () => {
  clearTimeout(objTimer)
  objTimer = setTimeout(() => {
    if (route.name === 'overview' && !document.hidden && !state.objsLoading && !document.querySelector('.editing')) loadObjs(false, true)
  }, 2000)
})
let timer = 0
onMounted(() => {
  bootstrap()
  window.addEventListener('keydown', onKey)
  // Dự phòng khi mất WebSocket: tự làm mới mỗi 60 giây khi đang xem Tổng quan (chạy ngầm). Không ép: server chỉ gọi
  // Facebook khi số liệu đã cũ (2–5 phút tuỳ mức dùng API) để khỏi bị Facebook giới hạn số lần gọi.
  timer = setInterval(() => {
    if (route.name === 'overview' && !document.hidden && state.auth.authed && !state.objsLoading && !document.querySelector('.editing')) { loadObjs(false, true); checkConn() }
  }, 60000)
})
onBeforeUnmount(() => { window.removeEventListener('keydown', onKey); clearInterval(timer); clearTimeout(objTimer); offObjects(); stopLive() })
</script>

<template>
  <TopProgress />
  <div v-if="!state.ready" class="boot"><Skeleton w="220px" h="14px" /></div>
  <LoginView v-else-if="showLogin" />
  <div v-else class="shell">
    <Sidebar />
    <div class="main">
      <Topbar />
      <AlertBanner />
      <RouterView v-slot="{ Component, route: r }">
        <component :is="Component" :key="r.name" class="view" />
      </RouterView>
    </div>
    <MobileTabbar />
    <CommandPalette />
  </div>
  <ConfirmHost />
  <ToastHost />
</template>

<style scoped>
.boot { min-height: 100vh; display: grid; place-items: center; }
.shell { display: grid; grid-template-columns: 250px minmax(0, 1fr); min-height: 100vh; }
.main { min-width: 0; padding: 30px 36px 80px; max-width: 1280px; width: 100%; margin: 0 auto; }
@media (max-width: 820px) {
  .shell { grid-template-columns: 1fr; }
  .shell > :deep(aside) { display: none; }
  .main { padding: 20px 16px 120px; }
}
</style>
