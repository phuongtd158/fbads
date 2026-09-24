<script setup>
import { computed, ref } from 'vue'
import { FlaskConical, ShieldCheck, Zap, Check } from 'lucide-vue-next'
import { state, saveSettings, modeOf } from '../../stores/app'
import { toast, toastError, confirm } from '../../stores/ui'
import { useRouter } from 'vue-router'
import { api } from '../../lib/api'
import InfoTip from '../../components/InfoTip.vue'

const router = useRouter()
const switching = ref(false)
const current = computed(() => modeOf(state.settings))
const modes = [
  { v: 'mock', icon: FlaskConical, title: 'Dùng thử', desc: 'Dữ liệu giả để làm quen giao diện. Không chạm vào Facebook.', tone: 'warning' },
  { v: 'dry', icon: ShieldCheck, title: 'Chạy thử', badge: 'Khuyên dùng', desc: 'Dùng Facebook thật nhưng lịch/rule chỉ ghi nhật ký, không thay đổi camp.', tone: 'info' },
  { v: 'live', icon: Zap, title: 'Chạy thật', desc: 'Lịch và rule sẽ bật/tắt camp và đổi ngân sách thật trên Facebook.', tone: 'success' },
]

async function pick(v) {
  if (v === current.value || switching.value) return
  const s = state.settings
  switching.value = true
  try {
    if (v !== 'mock') {
      if (!(s.has_accessToken && s.adAccountId)) {
        toast('Hãy kết nối Facebook (token và tài khoản quảng cáo) trước khi dùng chế độ này', 'error')
        return router.push('/settings/connection')
      }
      // Kiểm tra kết nối thật trước khi chuyển: token còn hạn, đủ quyền, tài khoản hoạt động
      const c = await api('connection')
      if (!c.ok) return toast('Kết nối Facebook đang lỗi nên chưa chuyển được: ' + c.error, 'error')
      if (c.token && c.token.missing && c.token.missing.length) return toast('Token đang thiếu quyền ' + c.token.missing.join(', ') + '. Hãy tạo lại token.', 'error')
      if (v === 'live' && c.accountActive === false) return toast('Tài khoản quảng cáo đang ở trạng thái “' + c.status + '” nên chưa thể chạy thật.', 'error')
    }
    if (v === 'live') {
      const ns = state.schedules.filter((x) => x.enabled).length, nr = state.rules.filter((x) => x.enabled).length
      const what = ns || nr ? `${ns} lịch và ${nr} rule đang bật sẽ chạy thật, bật/tắt camp và đổi ngân sách trên Facebook.` : 'Hiện chưa có lịch hoặc rule nào đang bật. Khi bạn tạo, chúng sẽ chạy thật ngay.'
      if (!await confirm('Chuyển sang chạy thật?', what + ' Bạn đã xem Nhật ký ở chế độ Chạy thử và thấy đúng ý chưa?', { ok: 'Chạy thật' })) return
    }
    await saveSettings({ mock: v === 'mock', dryRun: v !== 'live' }, { reset: true })
    toast('Đã chuyển chế độ: ' + modes.find((m) => m.v === v).title)
  } catch (e) { toastError(e) } finally { switching.value = false }
}
</script>

<template>
  <section class="card pad">
    <h3>Chế độ hoạt động <InfoTip tip="modes" /></h3>
    <p class="muted sub">Nên đi theo thứ tự: Dùng thử → Chạy thử vài ngày → Chạy thật. Bấm vào thẻ để đổi ngay.</p>
    <div class="modes">
      <button v-for="m in modes" :key="m.v" class="mc" :class="[m.tone, { on: current === m.v }]" :disabled="switching" @click="pick(m.v)">
        <span class="ic"><component :is="m.icon" :size="22" /></span>
        <b>{{ m.title }}</b>
        <em v-if="m.badge">{{ m.badge }}</em>
        <span class="d">{{ m.desc }}</span>
        <span class="tick"><Check :size="14" /></span>
      </button>
    </div>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.mc { position: relative; text-align: left; display: flex; flex-direction: column; gap: 6px; padding: 20px; border-radius: var(--r-lg); border: 1.5px solid var(--border); background: var(--surface); transition: .2s var(--ease); }
.mc:disabled { cursor: progress; opacity: .7; }
.mc:hover { border-color: var(--border-strong); transform: translateY(-2px); box-shadow: var(--shadow-md); }
.ic { width: 46px; height: 46px; border-radius: 15px; display: grid; place-items: center; margin-bottom: 6px; }
.warning .ic { background: var(--warning-soft); color: var(--warning); } .info .ic { background: var(--info-soft); color: var(--info); } .success .ic { background: var(--success-soft); color: var(--success); }
b { font-size: 16.5px; letter-spacing: -.01em; }
em { align-self: flex-start; font-style: normal; font-size: 12px; font-weight: 700; padding: 1px 9px; border-radius: 99px; background: var(--accent-soft); color: var(--accent); }
.d { color: var(--text-2); font-size: 14px; line-height: 1.5; }
.tick { position: absolute; top: 16px; right: 16px; width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; background: var(--accent); color: #fff; transform: scale(0); transition: transform .3s cubic-bezier(.34, 1.56, .64, 1); }
.mc.on { border-color: var(--accent); background: var(--accent-soft); } .mc.on .tick { transform: scale(1); }
@media (max-width: 900px) { .modes { grid-template-columns: 1fr; } }
</style>
