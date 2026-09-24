<script setup>
import { computed } from 'vue'
import { FlaskConical, ShieldCheck, Zap, Check } from 'lucide-vue-next'
import { state, saveSettings, modeOf } from '../../stores/app'
import { toast, toastError, confirm } from '../../stores/ui'
import { useRouter } from 'vue-router'

const router = useRouter()
const current = computed(() => modeOf(state.settings))
const modes = [
  { v: 'mock', icon: FlaskConical, title: 'Dùng thử', desc: 'Dữ liệu giả để làm quen giao diện. Không chạm vào Facebook.', tone: 'warning' },
  { v: 'dry', icon: ShieldCheck, title: 'Chạy thử', badge: 'Khuyên dùng', desc: 'Dùng Facebook thật nhưng lịch/rule chỉ ghi nhật ký, không thay đổi camp.', tone: 'info' },
  { v: 'live', icon: Zap, title: 'Chạy thật', desc: 'Lịch và rule sẽ bật/tắt camp và đổi ngân sách thật trên Facebook.', tone: 'success' },
]

async function pick(v) {
  if (v === current.value) return
  const s = state.settings
  if (v !== 'mock' && !(s.has_accessToken && s.adAccountId)) {
    toast('Hãy kết nối Facebook trước khi dùng chế độ này', 'error')
    return router.push('/settings/connection')
  }
  if (v === 'live' && !await confirm('Chuyển sang chạy thật?', 'Lịch và rule sẽ thực sự bật/tắt camp và thay đổi ngân sách trên Facebook.', { ok: 'Chạy thật' })) return
  try {
    await saveSettings({ mock: v === 'mock', dryRun: v !== 'live' }, { reset: true })
    toast('Đã chuyển chế độ: ' + modes.find((m) => m.v === v).title)
  } catch (e) { toastError(e) }
}
</script>

<template>
  <section class="card pad">
    <h3>Chế độ hoạt động</h3>
    <p class="muted sub">Nên đi theo thứ tự: Dùng thử → Chạy thử vài ngày → Chạy thật. Bấm vào thẻ để đổi ngay.</p>
    <div class="modes">
      <button v-for="m in modes" :key="m.v" class="mc" :class="[m.tone, { on: current === m.v }]" @click="pick(m.v)">
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
