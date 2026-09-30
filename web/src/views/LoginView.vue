<script setup>
import { ref, computed } from 'vue'
import { TrendingUp, Lock, CalendarClock, Zap, BellRing, Eye, EyeOff } from 'lucide-vue-next'
import { api } from '../lib/api'
import { state, afterLogin } from '../stores/app'
import ThemeToggle from '../components/ThemeToggle.vue'
import Btn from '../components/Btn.vue'

const username = ref('')
const name = ref('')
const pw = ref('')
const show = ref(false)
const err = ref('')
const input = ref(null)
const busy = ref(false)
// Đăng ký: chỉ khi server bật ALLOW_SIGNUP. Tài khoản mới có dữ liệu trống của riêng mình.
const register = ref(false)
const canSignup = computed(() => !!state.auth.signup)

// Chỉ gửi qua sự kiện submit của form (Enter hoặc bấm nút) — tránh gọi hai lần
async function submit() {
  if (!pw.value || busy.value || (register.value && !username.value.trim())) return
  err.value = ''; busy.value = true
  try {
    if (register.value) await api('register', 'POST', { username: username.value.trim(), name: name.value.trim(), password: pw.value })
    else await api('login', 'POST', { username: username.value.trim(), password: pw.value })
    await afterLogin()
  } catch (e) {
    err.value = e.fields ? Object.values(e.fields).filter(Boolean)[0] || e.message : e.message
    if (!register.value) pw.value = ''
    input.value && input.value.focus()
  } finally { busy.value = false }
}
function toggleMode() { register.value = !register.value; err.value = '' }
const features = [
  { i: CalendarClock, t: 'Hẹn giờ bật/tắt camp', d: 'Không cần dậy sớm nữa — tool chạy đúng giờ thay bạn.' },
  { i: Zap, t: 'Rule theo hiệu quả', d: 'Tự tắt camp CPA cao, tăng ngân sách camp ROAS tốt.' },
  { i: BellRing, t: 'Báo cáo qua Telegram', d: 'Nhận thông báo mỗi khi tool thay đổi camp.' },
]
</script>

<template>
  <div class="login">
    <aside class="brand">
      <div class="logo"><span><TrendingUp :size="20" /></span><b>FB Ads Auto</b></div>
      <h2>Tự động hoá quảng cáo Facebook,<br />bạn ngủ thêm một chút.</h2>
      <ul>
        <li v-for="f in features" :key="f.t"><span class="ic"><component :is="f.i" :size="19" /></span><div><b>{{ f.t }}</b><p>{{ f.d }}</p></div></li>
      </ul>
    </aside>
    <main>
      <div class="theme"><ThemeToggle /></div>
      <form class="card form" @submit.prevent="submit">
        <span class="lock"><Lock :size="22" /></span>
        <h1>{{ register ? 'Tạo tài khoản' : 'Đăng nhập' }}</h1>
        <p class="muted">{{ register ? 'Tài khoản mới có dữ liệu, token Facebook và lịch riêng' : 'Nhập tên đăng nhập và mật khẩu để tiếp tục' }}</p>
        <input v-model="username" class="input user" placeholder="Tên đăng nhập" autocomplete="username" autocapitalize="none" spellcheck="false" autofocus />
        <input v-if="register" v-model="name" class="input user" placeholder="Tên hiển thị (không bắt buộc)" autocomplete="name" />
        <div class="pw">
          <input ref="input" v-model="pw" class="input" :type="show ? 'text' : 'password'" placeholder="Mật khẩu" :autocomplete="register ? 'new-password' : 'current-password'" />
          <button type="button" class="eye" :aria-label="show ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'" @click="show = !show"><component :is="show ? EyeOff : Eye" :size="18" /></button>
        </div>
        <p class="err" :class="{ on: err }" role="alert">{{ err }}</p>
        <Btn type="submit" variant="primary" size="lg" block :loading="busy">{{ register ? 'Tạo tài khoản' : 'Đăng nhập' }}</Btn>
        <button v-if="canSignup" type="button" class="switch" @click="toggleMode">{{ register ? 'Đã có tài khoản? Đăng nhập' : 'Chưa có tài khoản? Tạo tài khoản' }}</button>
      </form>
    </main>
  </div>
</template>

<style scoped>
.login { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr); }
.brand {
  position: relative; overflow: hidden; padding: 44px 56px; display: flex; flex-direction: column; justify-content: center; gap: 34px; color: #fff;
  background: radial-gradient(600px 400px at 15% 10%, rgba(255, 255, 255, .22), transparent 60%), radial-gradient(500px 400px at 90% 100%, rgba(34, 211, 238, .35), transparent 60%), var(--accent-grad);
}
.logo { display: flex; align-items: center; gap: 12px; font-size: 19px; position: absolute; top: 40px; left: 56px; }
.logo span { width: 38px; height: 38px; border-radius: 12px; background: rgba(255, 255, 255, .2); backdrop-filter: blur(8px); display: grid; place-items: center; }
h2 { font-size: clamp(28px, 3vw, 40px); line-height: 1.18; letter-spacing: -.03em; font-weight: 700; max-width: 520px; }
ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 18px; max-width: 460px; }
li { display: flex; gap: 14px; padding: 16px; border-radius: 18px; background: rgba(255, 255, 255, .13); backdrop-filter: blur(10px); border: 1px solid rgba(255, 255, 255, .2); }
.ic { width: 40px; height: 40px; border-radius: 13px; background: rgba(255, 255, 255, .22); display: grid; place-items: center; flex: none; }
li b { display: block; font-size: 15.5px; } li p { font-size: 14px; opacity: .85; margin-top: 2px; }
main { position: relative; display: grid; place-items: center; padding: 28px; }
.theme { position: absolute; top: 24px; right: 24px; }
.form { width: min(410px, 100%); padding: 36px 32px; text-align: center; box-shadow: var(--shadow-md), var(--inset); animation: pop .5s var(--ease); }
.lock { width: 52px; height: 52px; border-radius: 17px; display: grid; place-items: center; margin: 0 auto 16px; background: var(--accent-soft); color: var(--accent); }
h1 { font-size: 26px; letter-spacing: -.03em; }
.form > p.muted { margin: 4px 0 24px; }
.user { margin-bottom: 10px; padding-top: 12px; padding-bottom: 12px; }
.pw { position: relative; }
.switch { margin-top: 14px; border: 0; background: none; color: var(--accent); font-weight: 600; font-size: 14px; }
.pw .input { padding-right: 46px; padding-top: 12px; padding-bottom: 12px; }
.eye { position: absolute; right: 6px; top: 50%; transform: translateY(-50%); width: 36px; height: 36px; border: 0; background: none; color: var(--text-3); border-radius: 10px; display: grid; place-items: center; }
.eye:hover { color: var(--text); background: var(--surface-3); }
.err { min-height: 22px; margin: 10px 0 8px; font-size: 14px; color: var(--danger); opacity: 0; transition: opacity .2s; }
.err.on { opacity: 1; }
@media (max-width: 900px) { .login { grid-template-columns: 1fr; } .brand { display: none; } }
</style>
