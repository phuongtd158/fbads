<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({ schedules: { type: Array, default: () => [] } })
const now = ref(new Date())
let t = 0
onMounted(() => { t = setInterval(() => (now.value = new Date()), 30000) })
onBeforeUnmount(() => clearInterval(t))

const today = computed(() => now.value.getDay())
const pct = (hhmm) => { const [h, m] = hhmm.split(':').map(Number); return ((h * 60 + m) / 1440) * 100 }
const nowPct = computed(() => ((now.value.getHours() * 60 + now.value.getMinutes()) / 1440) * 100)
const events = computed(() => props.schedules.filter((s) => s.enabled && s.days.includes(today.value)).map((s) => ({ ...s, left: pct(s.time), past: pct(s.time) < nowPct.value })))
const tone = (a) => (a === 'on' ? 'success' : a === 'off' ? 'danger' : 'info')
const label = (s) => `${s.time} · ${s.name}`
</script>

<template>
  <div class="tl">
    <div class="track">
      <i class="fill" :style="{ width: nowPct + '%' }" />
      <span v-for="e in events" :key="e.id" class="ev" :class="[tone(e.action), { past: e.past }]" :style="{ left: e.left + '%' }" :title="label(e)"><i /></span>
      <span class="now" :style="{ left: nowPct + '%' }"><em>Bây giờ</em></span>
    </div>
    <div class="ticks"><span v-for="h in [0, 6, 12, 18, 24]" :key="h" :style="{ left: (h / 24) * 100 + '%' }" class="num faint">{{ String(h).padStart(2, '0') }}:00</span></div>
    <p v-if="!events.length" class="none faint">Hôm nay chưa có lịch nào.</p>
  </div>
</template>

<style scoped>
.tl { padding: 8px 4px 0; }
.track { position: relative; height: 10px; border-radius: 99px; background: var(--surface-3); margin: 26px 8px 6px; }
.fill { position: absolute; inset: 0 auto 0 0; border-radius: 99px; background: var(--accent-grad); opacity: .35; }
.ev { position: absolute; top: 50%; width: 22px; height: 22px; margin: -11px 0 0 -11px; border-radius: 50%; display: grid; place-items: center; cursor: default; background: var(--surface); box-shadow: 0 2px 8px rgba(0, 0, 0, .18); transition: transform .2s var(--ease); z-index: 1; }
.ev:hover { transform: scale(1.35); z-index: 3; }
.ev i { width: 10px; height: 10px; border-radius: 50%; background: currentColor; }
.ev.success { color: var(--success); } .ev.danger { color: var(--danger); } .ev.info { color: var(--info); }
.ev.past { opacity: .5; }
.now { position: absolute; top: -8px; bottom: -8px; width: 2px; background: var(--accent); border-radius: 2px; z-index: 2; }
.now em { position: absolute; top: -20px; left: 50%; transform: translateX(-50%); font-style: normal; font-size: 11px; font-weight: 700; color: var(--accent); white-space: nowrap; }
.ticks { position: relative; height: 22px; margin: 0 8px; }
.ticks span { position: absolute; transform: translateX(-50%); font-size: 12px; }
.ticks span:first-child { transform: none; } .ticks span:last-child { transform: translateX(-100%); }
.none { text-align: center; font-size: 13.5px; margin-top: 4px; }
</style>
