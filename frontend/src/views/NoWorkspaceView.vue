<script setup>
import { ref } from 'vue'
import { FolderPlus, LogOut } from 'lucide-vue-next'
import { state, createWorkspace, logout } from '../stores/app'
import { toastError } from '../stores/ui'
import ThemeToggle from '../components/ThemeToggle.vue'
import Btn from '../components/Btn.vue'

// Đã đăng nhập nhưng không thuộc workspace nào (bị gỡ khỏi workspace cũ): tạo workspace riêng, hoặc chờ được thêm vào
const name = ref('')
async function create() {
  if (!name.value.trim()) return
  try { await createWorkspace(name.value.trim()) } catch (e) { toastError(e) }
}
</script>

<template>
  <main class="wrap">
    <div class="theme"><ThemeToggle /></div>
    <form class="card box" @submit.prevent="create">
      <span class="ic"><FolderPlus :size="22" /></span>
      <h1>Chưa có workspace</h1>
      <p class="muted">Tài khoản <b>{{ state.auth.user?.username }}</b> chưa thuộc workspace nào. Nhờ chủ workspace thêm bạn vào, hoặc tạo workspace của riêng bạn.</p>
      <input v-model="name" class="input" placeholder="Tên workspace mới" />
      <Btn type="submit" variant="primary" size="lg" block>Tạo workspace</Btn>
      <Btn class="out" :icon="LogOut" block :action="logout">Đăng xuất</Btn>
    </form>
  </main>
</template>

<style scoped>
.wrap { min-height: 100vh; display: grid; place-items: center; padding: 28px; position: relative; }
.theme { position: absolute; top: 24px; right: 24px; }
.box { width: min(430px, 100%); padding: 36px 32px; text-align: center; display: grid; gap: 14px; }
.ic { width: 52px; height: 52px; border-radius: 17px; display: grid; place-items: center; margin: 0 auto; background: var(--accent-soft); color: var(--accent); }
h1 { font-size: 24px; letter-spacing: -.03em; }
.out { margin-top: 4px; }
</style>
