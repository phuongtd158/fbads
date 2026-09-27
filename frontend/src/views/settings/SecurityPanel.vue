<script setup>
import { reactive, ref, computed } from 'vue'
import { ShieldCheck, ShieldAlert, Check, LogOut } from 'lucide-vue-next'
import { state, loadAuth, loadState, logout } from '../../stores/app'
import { toast } from '../../stores/ui'
import { api } from '../../lib/api'
import { validatePassword } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'

const f = reactive({ cur: '', next: '', again: '' })
const submitted = ref(false)
const touched = reactive({})
const has = computed(() => !!state.settings.has_passwordHash)

const errs = computed(() => {
  const e = {}
  if (has.value && !f.cur) e.cur = 'Nhập mật khẩu hiện tại'
  const p = validatePassword(f.next, f.cur)
  if (p.errors.newPassword) e.next = p.errors.newPassword
  if (f.again !== f.next) e.again = 'Hai lần nhập mật khẩu chưa khớp'
  return e
})
const show = (k) => (submitted.value || touched[k] ? errs.value[k] : '')

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

async function save() {
  submitted.value = true
  if (Object.keys(errs.value).length) { toast('Hãy sửa các mục báo lỗi trước khi lưu', 'error'); return }
  await api('password', 'POST', { currentPassword: f.cur, newPassword: f.next })
  f.cur = f.next = f.again = ''
  submitted.value = false
  for (const k of Object.keys(touched)) delete touched[k]
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
      <div v-if="!has" class="note warn"><ShieldAlert :size="20" /><div><b>Chưa đặt mật khẩu.</b> Ai mở được địa chỉ này đều điều khiển được quảng cáo của bạn. Bắt buộc phải đặt khi đưa tool lên mạng.</div></div>
      <div v-else class="note ok"><ShieldCheck :size="20" /><div>Đã bật đăng nhập bằng mật khẩu.</div></div>
      <div class="grid">
        <Field v-if="has" label="Mật khẩu hiện tại" :error="show('cur')"><input v-model="f.cur" class="input" type="password" autocomplete="current-password" @blur="touched.cur = true" /></Field>
        <Field label="Mật khẩu mới" :error="show('next')" hint="Ít nhất 8 ký tự, không chỉ gồm chữ số.">
          <input v-model="f.next" class="input" type="password" autocomplete="new-password" @blur="touched.next = true" />
          <span v-if="strength" class="meter" :class="strength[1]">Độ mạnh: <b>{{ strength[0] }}</b></span>
        </Field>
        <Field label="Nhập lại mật khẩu mới" :error="show('again')"><input v-model="f.again" class="input" type="password" autocomplete="new-password" @blur="touched.again = true" @keydown.enter="save" /></Field>
      </div>
      <Btn variant="primary" :icon="Check" :action="save">{{ has ? 'Đổi mật khẩu' : 'Đặt mật khẩu' }}</Btn>
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
