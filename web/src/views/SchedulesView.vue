<script setup>
import { ref, computed, onMounted } from 'vue'
import { Plus, Play, Pencil, Trash2, CalendarClock, Clock } from 'lucide-vue-next'
import { state, loadState, ensureObjs, loadObjs } from '../stores/app'
import { toast, toastError, confirm } from '../stores/ui'
import { api } from '../lib/api'
import { fmt } from '../lib/format'
import { scheduleTimes } from '../lib/validate'
import { describeFilter, matchFilter } from '../lib/bulkBudget'
import { DAY_LABEL, DAY_ORDER, SCHEDULE_PRESETS } from '../lib/constants'
import Btn from '../components/Btn.vue'
import Switch from '../components/Switch.vue'
import Badge from '../components/Badge.vue'
import EmptyState from '../components/EmptyState.vue'
import DayTimeline from '../components/DayTimeline.vue'
import ScheduleEditor from '../components/ScheduleEditor.vue'

const editor = ref(false)
const editing = ref(null)
const busy = ref({})

onMounted(() => ensureObjs())

const list = computed(() => [...state.schedules].sort((a, b) => scheduleTimes(a)[0].localeCompare(scheduleTimes(b)[0])))
const nameOf = (id) => (state.objs.find((o) => o.id === id) || { name: id }).name

function nextRun(s) {
  const now = new Date(), times = [...scheduleTimes(s)].sort()
  for (let i = 0; i < 8; i++) {
    for (const t of times) {
      const [h, m] = t.split(':').map(Number)
      const d = new Date(now); d.setDate(d.getDate() + i); d.setHours(h, m, 0, 0)
      if (d > now && s.days.includes(d.getDay())) return d
    }
  }
  return null
}
const rel = (d) => {
  const min = Math.round((d - new Date()) / 60000)
  if (min < 60) return `${min} phút nữa`
  if (min < 1440) return `${Math.floor(min / 60)} giờ${min % 60 ? ' ' + (min % 60) + ' phút' : ''} nữa`
  return `${Math.round(min / 1440)} ngày nữa`
}
const next = computed(() => list.value.filter((s) => s.enabled).map((s) => ({ s, d: nextRun(s) })).filter((x) => x.d).sort((a, b) => a.d - b.d)[0])

const actionTone = (a) => (a === 'on' ? 'success' : a === 'off' ? 'danger' : 'info')
const actionText = (s) => (s.action === 'on' ? 'Bật camp' : s.action === 'off' ? 'Tắt camp' : s.mode === 'percent' ? `${s.value > 0 ? '+' : ''}${s.value}% ngân sách` : s.mode === 'add' ? `${s.value > 0 ? '+' : '−'}${fmt(Math.abs(s.value))} ngân sách` : `Ngân sách = ${fmt(s.value)}`)
// Lịch theo điều kiện: số mục đang khớp lúc này (lúc chạy tool lọc lại)
const matchCount = (s) => (state.objsLoaded ? matchFilter(state.objs, s.filter).filter((o) => s.action !== 'budget' || o.dailyBudget != null).length : null)

function open(item) { editing.value = item; editor.value = true }
async function refresh() { await loadState() }

async function setEnabled(s, on) {
  busy.value[s.id] = true
  try { await api('schedules', 'POST', { ...s, enabled: on }); s.enabled = on; toast(on ? 'Đã bật lịch' : 'Đã tạm dừng lịch') } catch (e) { toastError(e) } finally { busy.value[s.id] = false }
}
async function runNow(s) {
  const dry = state.settings.dryRun && !state.settings.mock
  if (!await confirm('Chạy lịch ngay?', `“${s.name}” sẽ được thực hiện ngay bây giờ${dry ? ' (chế độ chạy thử: chỉ ghi nhật ký)' : ''}.`, { ok: 'Chạy ngay' })) return
  await api(`schedules/${s.id}/run`, 'POST')
  toast('Đã chạy — xem kết quả ở Nhật ký')
  loadObjs(true, true)
}
async function remove(s) {
  if (!await confirm('Xoá lịch này?', `“${s.name}” sẽ bị xoá vĩnh viễn.`, { ok: 'Xoá', danger: true })) return
  await api(`schedules/${s.id}`, 'DELETE')
  toast('Đã xoá lịch'); await refresh()
}
</script>

<template>
  <div>
    <Teleport to="#page-actions" defer><Btn variant="primary" :icon="Plus" @click="open(null)">Thêm lịch</Btn></Teleport>

    <section class="card today">
      <div class="th">
        <div><h3>Hôm nay</h3><p class="muted">{{ new Date().toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: 'long' }) }}</p></div>
        <div v-if="next" class="nx"><Clock :size="16" /><span>Tiếp theo: <b>{{ next.s.name }}</b> · {{ next.d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }} ({{ rel(next.d) }})</span></div>
      </div>
      <DayTimeline :schedules="state.schedules" />
    </section>

    <div class="presets">
      <span class="faint">Tạo nhanh</span>
      <button v-for="p in SCHEDULE_PRESETS" :key="p.name" class="chip" @click="open({ ...p.d })"><Plus :size="14" />{{ p.name }}</button>
    </div>

    <div v-if="list.length" class="grid stagger">
      <article v-for="s in list" :key="s.id" class="card it" :class="{ off: !s.enabled }">
        <div class="hd">
          <div class="tm">
            <span v-if="scheduleTimes(s).length === 1" class="time num">{{ scheduleTimes(s)[0] }}</span>
            <span v-else class="time num">{{ scheduleTimes(s).length }} lần/ngày</span>
            <Badge :tone="actionTone(s.action)">{{ actionText(s) }}</Badge>
          </div>
          <Switch :model-value="s.enabled" :loading="busy[s.id]" :label="'Bật/tắt lịch ' + s.name" @update:model-value="(v) => setEnabled(s, v)" />
        </div>
        <h4>{{ s.name }}</h4>
        <div v-if="scheduleTimes(s).length > 1" class="tlist num">{{ scheduleTimes(s).join(' · ') }}</div>
        <div class="days"><span v-for="d in DAY_ORDER" :key="d" :class="{ on: s.days.includes(d) }">{{ DAY_LABEL[d] }}</span></div>
        <div v-if="s.targetMode === 'filter'" class="tags"><span class="tag flt" :title="describeFilter(s.filter)">Theo điều kiện: {{ describeFilter(s.filter) }}</span><span v-if="matchCount(s) != null" class="tag">hiện khớp {{ matchCount(s) }}</span></div>
        <div v-else class="tags"><span v-for="id in s.targets.slice(0, 3)" :key="id" class="tag" :title="nameOf(id)">{{ nameOf(id) }}</span><span v-if="s.targets.length > 3" class="tag">+{{ s.targets.length - 3 }}</span></div>
        <div class="acts">
          <Btn size="sm" :icon="Play" :action="() => runNow(s)">Chạy ngay</Btn>
          <Btn size="sm" :icon="Pencil" @click="open(s)">Sửa</Btn>
          <span class="grow" />
          <Btn size="sm" variant="ghost danger" :icon="Trash2" :action="() => remove(s)" aria-label="Xoá" />
        </div>
      </article>
    </div>
    <section v-else class="card"><EmptyState :icon="CalendarClock" title="Chưa có lịch nào" text="Chọn một mẫu ở trên hoặc bấm “Thêm lịch”. Tool sẽ tự chạy đúng giờ, bạn không cần dậy sớm nữa.">
      <Btn variant="primary" :icon="Plus" @click="open(null)">Thêm lịch đầu tiên</Btn></EmptyState></section>

    <p class="note faint">Giờ chạy tính theo múi giờ <b>{{ state.settings.timezone }}</b>. Nếu máy bật trễ tối đa 10 phút so với giờ hẹn, tool vẫn chạy bù. <RouterLink to="/help/schedules">Xem hướng dẫn về lịch →</RouterLink></p>
    <ScheduleEditor v-model="editor" :item="editing" @saved="refresh" />
  </div>
</template>

<style scoped>
.today { padding: 20px 22px 14px; margin-bottom: 18px; }
.th { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.th h3 { font-size: 17px; letter-spacing: -.02em; }
.th p { font-size: 13.5px; text-transform: capitalize; }
.nx { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); font-size: 13.5px; }
.presets { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 18px; }
.presets > span { font-size: 13px; font-weight: 600; margin-right: 4px; }
.chip { display: inline-flex; align-items: center; gap: 6px; border: 1px dashed var(--border-strong); background: transparent; padding: 7px 14px; border-radius: 99px; font-weight: 600; font-size: 13.5px; color: var(--text-2); transition: .18s var(--ease); }
.chip:hover { border-style: solid; border-color: var(--accent); color: var(--accent); background: var(--accent-soft); transform: translateY(-1px); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }
.it { padding: 20px; display: flex; flex-direction: column; gap: 12px; transition: transform .2s var(--ease), box-shadow .2s, opacity .2s; }
.it:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.it.off { opacity: .6; }
.hd { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.tm { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.time { font-size: 34px; font-weight: 750; letter-spacing: -.04em; line-height: 1; }
h4 { font-size: 15.5px; font-weight: 620; }
.tlist { font-size: 13.5px; font-weight: 600; color: var(--text-2); margin-top: -6px; }
.days { display: flex; gap: 5px; }
.days span { width: 32px; height: 32px; border-radius: 50%; display: grid; place-items: center; font-size: 12px; font-weight: 650; background: var(--surface-3); color: var(--text-3); }
.days span.on { background: var(--accent-grad); color: #fff; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; min-height: 26px; }
.tag.flt { max-width: 100%; background: var(--accent-soft); color: var(--accent); }
.tag { background: var(--surface-3); color: var(--text-2); padding: 2px 10px; border-radius: 8px; font-size: 13px; max-width: 190px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.acts { display: flex; gap: 8px; margin-top: auto; padding-top: 14px; border-top: 1px solid var(--border); }
.grow { flex: 1; }
.note { margin-top: 18px; font-size: 13px; }
</style>
