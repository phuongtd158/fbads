<script setup>
import { reactive, ref, computed } from 'vue'
import { ShieldCheck, ShieldAlert, Check, LogOut, UserPlus } from 'lucide-vue-next'
import { state, afterLogin, logout } from '../../stores/app'
import { toast } from '../../stores/ui'
import { api } from '../../lib/api'
import { validatePassword } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'

// Chưa có tài khoản nào (chế độ mở): tạo tài khoản đầu tiên, thành chủ workspace hiện tại.
// Đã đăng nhập: đổi mật khẩu của chính mình.
const setup = computed(() => !!state.auth.setup)
const f = reactive({ username: '', name: '', cur: '', next: '', again: '' })
const submitted = ref(false)
const touched = reactive({})
const serverErrs = ref({})

const errs = computed(() => {
  const e = {}
  if (setup.value && !/^[a-z0-9._@+-]{3,190}$/.test(f.username.trim().toLowerCase())) e.username = 'Tên đăng nhập 3–190 ký tự: chữ thường không dấu, số và . _ @ + -'
  if (!setup.value && !f.cur) e.cur = 'Nhập mật khẩu hiện tại'
  const p = validatePassword(f.next, f.cur)
  if (p.errors.newPassword) e.next = p.errors.newPassword
  if (f.again !== f.next) e.again = 'Hai lần nhập mật khẩu chưa khớp'
  return e
})
const show = (k) => serverErrs.value[k] || (submitted.value || touched[k] ? errs.value[k] : '')

// Đo độ mạnh đơn giản để người dùng thấy ngay
const strength = computed(() => {
  const p = f.next
  if (!p) return null
  let s = 0
  if (p.length >= 8) s++
  if (p.length >= 12) s++
  if (/[a-z]/.test(p) && /[A-Z]/.test(p)) s++
  if (/\d/.test(p) && /[^A-Za-z0-9]/.test(p)) s++
  return [['Yếu', 'danger'], ['Yếu', 'danger'], ['Trung bình', 'warning'], ['Khá', 'success'], ['Mạnh', 'success']][s]
})

function reset() {
  Object.assign(f, { username: '', name: '', cur: '', next: '', again: '' })
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
}

async function save() {
  submitted.value = true
  serverErrs.value = {}
  if (Object.keys(errs.value).length) { toast('Hãy sửa các mục báo lỗi trước khi lưu', 'error'); return }
  try {
    if (setup.value) await api('setup', 'POST', { username: f.username, name: f.name, password: f.next })
    else await api('password', 'POST', { currentPassword: f.cur, newPassword: f.next })
  } catch (e) {
    if (e.fields) { serverErrs.value = { username: e.fields.username, cur: e.fields.currentPassword, next: e.fields.password || e.fields.newPassword }; return }
    throw e
  }
  const wasSetup = setup.value
  reset()
  await afterLogin()
  toast(wasSetup ? 'Đã tạo tài khoản. Từ giờ cần đăng nhập để vào tool.' : 'Đã đổi mật khẩu. Các thiết bị khác đã bị đăng xuất.')
}
</script>

<template>
  <section class="card pad">
    <h3>Bảo mật</h3>
    <p class="muted sub">Tài khoản đăng nhập bảo vệ token Facebook và quyền điều khiển quảng cáo của bạn.</p>

    <template v-if="setup">
      <div class="note warn"><ShieldAlert :size="20" /><div><b>Chưa có tài khoản nào.</b> Ai mở được địa chỉ này đều điều khiển được quảng cáo của bạn. Tạo tài khoản đầu tiên (bạn sẽ là chủ workspace này), sau đó có thể mời thêm người ở mục Thành viên.</div></div>
      <div class="grid">
        <Field label="Tên đăng nhập" :error="show('username')"><input v-model="f.username" class="input" autocomplete="username" autocapitalize="none" @blur="touched.username = true" /></Field>
        <Field label="Tên hiển thị" hint="Không bắt buộc"><input v-model="f.name" class="input" autocomplete="name" /></Field>
      </div>
    </template>
    <div v-else-if="state.auth.envManaged" class="note inf"><ShieldCheck :size="20" /><div>Mật khẩu của <b>admin</b> được đặt bằng biến môi trường <code>APP_PASSWORD</code> trên server. Muốn đổi, sửa biến đó rồi khởi động lại tool.</div></div>
    <div v-else class="note ok"><ShieldCheck :size="20" /><div>Đang đăng nhập bằng tài khoản <b>{{ state.auth.user?.username }}</b>.</div></div>

    <template v-if="setup || !state.auth.envManaged">
      <div class="grid">
        <Field v-if="!setup" label="Mật khẩu hiện tại" :error="show('cur')"><input v-model="f.cur" class="input" type="password" autocomplete="current-password" @blur="touched.cur = true" /></Field>
        <Field :label="setup ? 'Mật khẩu' : 'Mật khẩu mới'" :error="show('next')" hint="Ít nhất 8 ký tự, không chỉ gồm chữ số.">
          <input v-model="f.next" class="input" type="password" autocomplete="new-password" @blur="touched.next = true" />
          <span v-if="strength" class="meter" :class="strength[1]">Độ mạnh: <b>{{ strength[0] }}</b></span>
        </Field>
        <Field label="Nhập lại mật khẩu" :error="show('again')"><input v-model="f.again" class="input" type="password" autocomplete="new-password" @blur="touched.again = true" @keydown.enter="save" /></Field>
      </div>
      <Btn variant="primary" :icon="setup ? UserPlus : Check" :action="save">{{ setup ? 'Tạo tài khoản' : 'Đổi mật khẩu' }}</Btn>
    </template>

    <div v-if="state.auth.required" class="out"><Btn :icon="LogOut" :action="logout">Đăng xuất</Btn></div>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.note { display: flex; gap: 12px; padding: 13px 16px; border-radius: 14px; margin-bottom: 20px; font-size: 14.5px; align-items: flex-start; } .note svg { flex: none; margin-top: 1px; }
.note.ok { background: var(--success-soft); color: var(--success); } .note.warn { background: var(--warning-soft); color: var(--warning); } .note.inf { background: var(--surface-3); color: var(--text); }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.meter { display: block; margin-top: 6px; font-size: 12.5px; color: var(--text-3); }
.meter.danger b { color: var(--danger); } .meter.warning b { color: var(--warning); } .meter.success b { color: var(--success); }
.out { margin-top: 22px; padding-top: 20px; border-top: 1px solid var(--border); }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
