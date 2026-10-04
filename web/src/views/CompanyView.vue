<script setup>
// Các bản báo cáo lên hệ thống công ty theo mốc: xem số Facebook đã cộng, nhập Đơn hàng / DSO sau VAT, ghi chú, rồi gửi.
import { ref, reactive, computed, onMounted } from 'vue'
import { Send, Save, Trash2, FilePlus2, Building2, Settings, ChevronDown } from 'lucide-vue-next'
import { state } from '../stores/app'
import { toast, confirm } from '../stores/ui'
import { api } from '../lib/api'
import { fmt } from '../lib/format'
import { METRICS, SLOTS, missingMetrics, validateReportPatch } from '../lib/companyReport'
import Btn from '../components/Btn.vue'
import Badge from '../components/Badge.vue'
import Callout from '../components/Callout.vue'
import EmptyState from '../components/EmptyState.vue'
import MoneyInput from '../components/MoneyInput.vue'

const cfg = ref(null)
const list = ref([])
const edits = reactive({}) // { [id]: { metrics: {...}, notes, issue, resolution } } bản đang sửa
const open = reactive({})  // ô ghi chú đang mở
const errs = reactive({})
const slot = ref(nowSlot())

function nowSlot() { const h = new Date().getHours(); return [...SLOTS].reverse().find((s) => h >= s) || 9 }

const editOf = (r) => (edits[r.id] ||= { metrics: { ...r.metrics }, notes: r.notes || '', issue: r.issue || '', resolution: r.resolution || '' })
async function load() {
  const r = await api('company')
  cfg.value = r.config
  list.value = r.reports
  for (const k of Object.keys(edits)) delete edits[k]
  for (const x of r.reports) editOf(x)
}
onMounted(load)

const STATUS = { pending: ['Chờ gửi', 'warning'], sent: ['Đã gửi', 'success'], failed: ['Gửi lỗi', 'danger'], exists: ['Công ty đã có', 'info'] }
const dateText = (iso) => { const t = new Date(`${iso}T00:00:00`).toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit' }); return t.charAt(0).toUpperCase() + t.slice(1) }
// Nhóm theo ngày + mốc, mới nhất trước
const groups = computed(() => {
  const by = new Map()
  for (const r of [...list.value].sort((a, b) => (b.date + String(b.slot).padStart(2, '0')).localeCompare(a.date + String(a.slot).padStart(2, '0')))) {
    const k = `${r.date}:${r.slot}`
    if (!by.has(k)) by.set(k, { key: k, date: r.date, slot: r.slot, items: [] })
    by.get(k).items.push(r)
  }
  return [...by.values()]
})
const label = (r) => [r.teamCode, r.teamName].filter(Boolean).join(' · ') || r.teamId
const canSend = computed(() => cfg.value && cfg.value.mode === 'approve' && !state.settings.mock)
const locked = (r) => r.status === 'sent' || r.status === 'exists'
const missing = (r) => missingMetrics({ metrics: editOf(r).metrics })

async function save(r, quiet = false) {
  const e = editOf(r)
  const v = validateReportPatch(e)
  errs[r.id] = v.errors
  if (!v.ok) throw new Error(v.first)
  const saved = await api(`company/reports/${r.id}`, 'POST', e)
  Object.assign(r, saved)
  if (!quiet) toast('Đã lưu')
}
async function send(r) {
  await save(r, true)
  const miss = missing(r)
  if (miss.length) { toast(`Còn thiếu: ${miss.map((m) => m.label).join(', ')}`, 'error'); return }
  if (!await confirm('Gửi lên hệ thống công ty?', `Báo cáo ${label(r)} · ${r.slot}h ngày ${r.date.slice(8)}/${r.date.slice(5, 7)}: chi ${fmt(r.metrics.spend)}, ${fmt(r.metrics.orders)} đơn, DSO ${fmt(r.metrics.dso_after)}. Gửi xong không sửa ở tool được nữa.`, { ok: 'Gửi báo cáo' })) return
  try {
    Object.assign(r, await api(`company/reports/${r.id}/send`, 'POST'))
    toast('Đã gửi báo cáo lên công ty')
  } finally { await load() }
}
async function remove(r) {
  if (!await confirm('Xoá bản báo cáo này?', 'Chỉ xoá trong tool, không đụng tới hệ thống công ty.', { ok: 'Xoá', danger: true })) return
  await api(`company/reports/${r.id}`, 'DELETE')
  await load()
}
async function build() {
  const r = await api('company/reports/build', 'POST', { slot: slot.value })
  list.value = r.reports
  for (const x of r.reports) editOf(x)
  toast(`Đã tạo báo cáo mốc ${slot.value}h`)
}
</script>

<template>
  <div v-if="cfg">
    <Teleport to="#page-actions" defer>
      <div v-if="cfg.teams.length" class="mk">
        <select v-model.number="slot" class="input" aria-label="Mốc báo cáo"><option v-for="s in SLOTS" :key="s" :value="s">Mốc {{ s }}h</option></select>
        <Btn variant="primary" :icon="FilePlus2" :action="build">Tạo báo cáo ngay</Btn>
      </div>
    </Teleport>

    <Callout v-if="!cfg.teams.length" tone="info">Chưa có Team nào. Vào <RouterLink to="/settings/company">Cài đặt → Báo cáo công ty</RouterLink> để nhập tài khoản hệ thống công ty và chọn Team.</Callout>
    <Callout v-else-if="!cfg.enabled" tone="info">Báo cáo theo mốc đang tắt: tool không tự tạo báo cáo lúc {{ cfg.slots.map((s) => s + 'h').join(', ') }}. Bạn vẫn tạo tay được bằng nút <b>Tạo báo cáo ngay</b>. <RouterLink to="/settings/company">Bật trong Cài đặt</RouterLink></Callout>
    <Callout v-if="cfg.mode === 'preview'" tone="info">Chế độ <b>Chỉ xem</b>: tool không gửi gì lên hệ thống công ty. Đổi sang “Duyệt trước khi gửi” ở <RouterLink to="/settings/company">Cài đặt</RouterLink> khi số đã khớp.</Callout>
    <Callout v-if="state.settings.mock" tone="warning">Tool đang dùng dữ liệu giả (Dùng thử) nên số chỉ để xem thử và không gửi được lên công ty.</Callout>

    <template v-if="groups.length">
      <section v-for="g in groups" :key="g.key" class="grp">
        <h3><span class="num">{{ g.slot }}h</span> <span class="muted">{{ dateText(g.date) }}{{ g.slot === 9 ? ' · chốt cả ngày' : ' · lũy kế đến ' + g.slot + 'h' }}</span></h3>
        <article v-for="r in g.items" :key="r.id" class="card rp" :class="r.status">
          <header>
            <div class="tt"><Building2 :size="17" /><b>{{ label(r) }}</b><span class="faint" :title="(r.campaigns || []).join('\n')">{{ (r.campaigns || []).length }} chiến dịch</span></div>
            <Badge :tone="STATUS[r.status][1]" dot>{{ STATUS[r.status][0] }}</Badge>
          </header>
          <div class="nums">
            <label v-for="m in METRICS" :key="m.key" class="n" :class="{ need: !m.fb && editOf(r).metrics[m.key] == null && !locked(r), bad: errs[r.id] && errs[r.id][m.key] }">
              <span>{{ m.label }}<i v-if="m.fb" class="fbt">FB</i></span>
              <b v-if="locked(r)" class="num">{{ fmt(r.metrics[m.key]) }}</b>
              <MoneyInput v-else v-model="editOf(r).metrics[m.key]" :placeholder="m.fb ? '0' : 'Nhập…'" :aria-label="m.label" />
            </label>
          </div>
          <button v-if="!locked(r) || r.notes || r.issue || r.resolution" type="button" class="more" :aria-expanded="!!open[r.id]" @click="open[r.id] = !open[r.id]"><ChevronDown :size="15" :class="{ rot: open[r.id] }" />Ghi chú, vấn đề, hướng xử lý</button>
          <div v-if="open[r.id]" class="txt">
            <label class="full"><span>Ghi chú</span><textarea v-model="editOf(r).notes" class="input" rows="2" maxlength="2000" :readonly="locked(r)" /></label>
            <label><span>Vấn đề</span><textarea v-model="editOf(r).issue" class="input" rows="2" maxlength="2000" :readonly="locked(r)" /></label>
            <label><span>Hướng xử lý</span><textarea v-model="editOf(r).resolution" class="input" rows="2" maxlength="2000" :readonly="locked(r)" /></label>
          </div>
          <p v-if="r.error" class="er">{{ r.error }}</p>
          <p v-if="r.status === 'sent'" class="ok">Đã gửi lúc {{ new Date(r.sentAt).toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' }) }}{{ r.remoteStatus === 'LATE' ? ' · công ty ghi nhận nộp muộn' : '' }}</p>
          <footer v-if="!locked(r)">
            <Btn size="sm" :icon="Save" :action="() => save(r)">Lưu</Btn>
            <Btn v-if="canSend" size="sm" variant="primary" :icon="Send" :action="() => send(r)">Gửi lên công ty</Btn>
            <span v-if="missing(r).length" class="faint hint">Còn thiếu {{ missing(r).map((m) => m.label).join(', ') }}</span>
            <span class="grow" />
            <Btn size="sm" variant="ghost danger" :icon="Trash2" aria-label="Xoá bản báo cáo" :action="() => remove(r)" />
          </footer>
        </article>
      </section>
    </template>
    <section v-else-if="cfg.teams.length" class="card"><EmptyState :icon="Building2" title="Chưa có báo cáo nào" :text="cfg.enabled ? `Tool sẽ tự tạo báo cáo lúc ${cfg.slots.map((s) => s + 'h').join(', ')}. Muốn xem ngay thì chọn mốc rồi bấm “Tạo báo cáo ngay”.` : 'Chọn mốc rồi bấm “Tạo báo cáo ngay” để xem số Facebook đã cộng theo Team.'">
      <Btn :icon="Settings" @click="$router.push('/settings/company')">Cài đặt báo cáo công ty</Btn></EmptyState></section>
  </div>
</template>

<style scoped>
.mk { display: flex; gap: 8px; align-items: center; } .mk select { width: auto; }
.co { margin-bottom: 12px; }
.grp { margin-bottom: 22px; }
.grp h3 { font-size: 16px; letter-spacing: -.01em; margin: 0 0 10px; display: flex; gap: 8px; align-items: baseline; }
.grp h3 .num { font-size: 20px; font-weight: 750; } .grp h3 .muted { font-size: 13.5px; font-weight: 550; }
.rp { padding: 18px 20px; margin-bottom: 12px; display: flex; flex-direction: column; gap: 14px; }
.rp.sent { opacity: .85; }
header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.tt { display: flex; align-items: center; gap: 8px; min-width: 0; flex-wrap: wrap; } .tt b { font-size: 15px; } .tt .faint { font-size: 13px; }
.nums { display: grid; grid-template-columns: repeat(auto-fill, minmax(128px, 1fr)); gap: 10px 12px; }
.n { display: flex; flex-direction: column; gap: 5px; font-size: 13px; color: var(--text-2); font-weight: 600; }
.n > span { display: flex; align-items: center; gap: 6px; }
.fbt { font-style: normal; font-size: 10.5px; padding: 0 5px; border-radius: 5px; background: var(--info-soft); color: var(--info); }
.n b { font-size: 17px; color: var(--text); }
.n.need .input, .n.need :deep(.input) { border-color: var(--warning); background: var(--warning-soft); }
.n.bad .input, .n.bad :deep(.input) { border-color: var(--danger); }
.more { align-self: flex-start; display: inline-flex; gap: 6px; align-items: center; border: 0; background: none; padding: 0; color: var(--text-2); font-weight: 600; font-size: 13.5px; }
.more .rot { transform: rotate(180deg); }
.txt { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 12px; } .txt .full { grid-column: 1 / -1; }
.txt label { display: flex; flex-direction: column; gap: 5px; font-size: 13px; font-weight: 600; color: var(--text-2); }
.txt textarea { resize: vertical; min-height: 60px; font: inherit; }
.er { margin: 0; color: var(--danger); font-size: 13.5px; } .ok { margin: 0; color: var(--success); font-size: 13.5px; }
footer { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding-top: 12px; border-top: 1px solid var(--border); }
.hint { font-size: 13px; } .grow { flex: 1; }
@media (max-width: 600px) { .nums { grid-template-columns: 1fr 1fr; } .txt { grid-template-columns: 1fr; } .mk select { max-width: 130px; } }
</style>
