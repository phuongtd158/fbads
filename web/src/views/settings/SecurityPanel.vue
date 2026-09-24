<script setup>
import { reactive } from 'vue'
import { ShieldCheck, ShieldAlert, Check, LogOut } from 'lucide-vue-next'
import { state, loadAuth, loadState, logout } from '../../stores/app'
import { toast } from '../../stores/ui'
import { api } from '../../lib/api'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'

const f = reactive({ cur: '', next: '', again: '' })

async function save() {
  if (f.next.length < 8) return toast('Mật khẩu mới cần ít nhất 8 ký tự', 'error')
  if (f.next !== f.again) return toast('Hai lần nhập mật khẩu chưa khớp', 'error')
  await api('password', 'POST', { currentPassword: f.cur, newPassword: f.next })
  f.cur = f.next = f.again = ''
  await loadAuth(); await loadState()
  toast('Đã lưu mật khẩu')
}
</script>

<template>
  <section class="card pad">
    <h3>Bảo mật</h3>
    <p class="muted sub">Mật khẩu đăng nhập bảo vệ token Facebook và quyền điều khiển quảng cáo của bạn.</p>

    <div v-if="state.auth.envManaged" class="note inf"><ShieldCheck :size="20" /><div>Mật khẩu được đặt bằng biến môi trường <code>APP_PASSWORD</code> trên server. Muốn đổi, sửa biến đó rồi khởi động lại tool.</div></div>
    <template v-else>
      <div v-if="!state.settings.has_passwordHash" class="note warn"><ShieldAlert :size="20" /><div><b>Chưa đặt mật khẩu.</b> Ai mở được địa chỉ này đều điều khiển được quảng cáo của bạn. Bắt buộc phải đặt khi đưa tool lên mạng.</div></div>
      <div v-else class="note ok"><ShieldCheck :size="20" /><div>Đã bật đăng nhập bằng mật khẩu.</div></div>
      <div class="grid">
        <Field v-if="state.settings.has_passwordHash" label="Mật khẩu hiện tại"><input v-model="f.cur" class="input" type="password" autocomplete="current-password" /></Field>
        <Field label="Mật khẩu mới" hint="Ít nhất 8 ký tự."><input v-model="f.next" class="input" type="password" autocomplete="new-password" /></Field>
        <Field label="Nhập lại mật khẩu mới"><input v-model="f.again" class="input" type="password" autocomplete="new-password" @keydown.enter="save" /></Field>
      </div>
      <Btn variant="primary" :icon="Check" :action="save">{{ state.settings.has_passwordHash ? 'Đổi mật khẩu' : 'Đặt mật khẩu' }}</Btn>
    </template>

    <div v-if="state.auth.required" class="out"><Btn :icon="LogOut" :action="logout">Đăng xuất</Btn></div>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.note { display: flex; gap: 12px; padding: 13px 16px; border-radius: 14px; margin-bottom: 20px; font-size: 14.5px; align-items: flex-start; } .note svg { flex: none; margin-top: 1px; }
.note.ok { background: var(--success-soft); color: var(--success); } .note.warn { background: var(--warning-soft); color: var(--warning); } .note.inf { background: var(--surface-3); color: var(--text); }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.out { margin-top: 22px; padding-top: 20px; border-top: 1px solid var(--border); }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
