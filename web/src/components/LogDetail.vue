<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CheckCircle2, AlertCircle, FlaskConical, Copy, RotateCcw, Undo2, ExternalLink, ArrowRight, ChevronDown, Lightbulb, Bell, SkipForward } from 'lucide-vue-next'
import { state } from '../stores/app'
import { toast, confirm } from '../stores/ui'
import { api } from '../lib/api'
import { fmt, fmtDec } from '../lib/format'
import { rich } from '../lib/rich'
import { hintsFor, kindOf, KIND_LABEL, MODE_LABEL } from '../lib/logHints'
import { METRICS, RANGE_LABEL } from '../lib/constants'
import { undoBlocker } from '../lib/validate'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Badge from './Badge.vue'
import Callout from './Callout.vue'

const props = defineProps({ modelValue: Boolean, log: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue', 'retried'])
const router = useRouter()
const showTech = ref(false)

const l = computed(() => props.log || {})
const kind = computed(() => kindOf(l.value))
const failed = computed(() => l.value.ok === false)
const dry = computed(() => !!l.value.dry)
const isNotify = computed(() => !!(l.value.action && l.value.action.type === 'notify'))
const status = computed(() => (failed.value ? { tone: 'danger', text: 'Lỗi', icon: AlertCircle }
  : l.value.skipped ? { tone: 'warning', text: 'Bỏ qua', icon: SkipForward }
  : isNotify.value ? { tone: 'info', text: 'Cảnh báo', icon: Bell }
  : dry.value ? { tone: 'warning', text: 'Chạy thử', icon: FlaskConical } : { tone: 'success', text: 'Thành công', icon: CheckCircle2 }))
const err = computed(() => l.value.error || null)
const hints = computed(() => (failed.value ? hintsFor(l.value) : []))
const hasDetail = computed(() => !!(l.value.before || l.value.after || l.value.condition || l.value.action || err.value))

// Lịch/rule nguồn còn tồn tại không (để cho mở hoặc chạy lại)
const ref_ = computed(() => (kind.value === 'schedule' ? state.schedules : kind.value === 'rule' ? state.rules : []).find((x) => x.id === l.value.refId))

const when = computed(() => (l.value.ts ? new Date(l.value.ts).toLocaleString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : ''))
const actionText = computed(() => {
  const a = l.value.action
  if (!a) return ''
  if (a.type === 'on') return 'Bật camp'
  if (a.type === 'off') return 'Tắt camp'
  if (a.type === 'notify') return 'Chỉ gửi cảnh báo (không đổi camp)'
  if (a.type === 'budget') {
    const parts = [a.mode === 'percent' ? `${a.value > 0 ? 'Tăng' : 'Giảm'} ${Math.abs(a.value)}% ngân sách` : a.mode === 'add' ? `${a.value > 0 ? 'Cộng thêm' : 'Trừ bớt'} ${fmt(Math.abs(a.value))} ngân sách` : `Đặt ngân sách = ${fmt(a.value)}`]
    if (a.max) parts.push(`trần ${fmt(a.max)}`)
    if (a.min) parts.push(`sàn ${fmt(a.min)}`)
    return parts.join(', ')
  }
  return ''
})
const cond = computed(() => {
  const c = l.value.condition
  if (!c) return null
  const val = c.actualInf ? '∞ (chưa có kết quả)' : c.actual == null ? '–' : c.metric === 'roas' ? fmtDec(c.actual) : fmt(c.actual)
  const th = c.metric === 'roas' ? c.threshold : fmt(c.threshold)
  return `${METRICS[c.metric]} (${RANGE_LABEL[c.range || 'today']}) = ${val} ${c.op === '>' ? 'lớn hơn' : 'nhỏ hơn'} ngưỡng ${th}` + (c.minSpend ? ` · đã chi ${fmt(c.spend)} (≥ ${fmt(c.minSpend)} tối thiểu)` : '')
})
const stateText = (s) => (s ? ({ ACTIVE: 'Đang chạy', PAUSED: 'Tạm dừng' }[s] || s) : '–')
const rows = computed(() => {
  const b = l.value.before, a = l.value.after
  if (!b && !a) return []
  const out = []
  if (a && a.status !== undefined) out.push(['Trạng thái', stateText(b && (b.effective || b.status)), stateText(a.status)])
  if (a && a.dailyBudget !== undefined) out.push(['Ngân sách / ngày', b && b.dailyBudget != null ? fmt(b.dailyBudget) : '–', fmt(a.dailyBudget)])
  return out
})
const metrics = computed(() => l.value.before && l.value.before.metrics)

const techRows = computed(() => {
  const e = err.value
  if (!e) return []
  return [['Thông báo', e.message], ['Mã lỗi Facebook', e.code], ['Mã phụ', e.subcode], ['Loại', e.type], ['fbtrace_id', e.fbtraceId], ['HTTP', e.httpStatus], ['Thông báo gốc', e.rawMessage && e.rawMessage !== e.message ? e.rawMessage : ''], ['Lỗi hệ thống', e.systemMessage]]
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
})
const request = computed(() => err.value && err.value.request)

async function copy(text, okMsg = 'Đã sao chép') {
  try { await navigator.clipboard.writeText(text) } catch {
    const t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select(); document.execCommand('copy'); t.remove()
  }
  toast(okMsg)
}
const copyAll = () => copy(JSON.stringify(props.log, null, 2), 'Đã sao chép toàn bộ chi tiết')

function go(to) { emit('update:modelValue', false); router.push(to) }

async function retry() {
  const isRule = kind.value === 'rule'
  if (!await confirm(isRule ? 'Kiểm tra lại rule?' : 'Chạy lại lịch này?', isRule ? `Tool sẽ kiểm tra lại toàn bộ rule ngay bây giờ. Kết quả sẽ hiện thêm trong Nhật ký.` : `Lịch “${ref_.value.name}” sẽ được thực hiện lại ngay. Kết quả sẽ hiện thêm trong Nhật ký.`, { ok: isRule ? 'Kiểm tra lại' : 'Chạy lại' })) return
  await api(isRule ? 'rules/run' : `schedules/${ref_.value.id}/run`, 'POST')
  toast('Đã chạy lại — xem dòng nhật ký mới')
  emit('retried'); emit('update:modelValue', false)
}
// Hoàn tác: chỉ với thay đổi thật đã thành công; lý do không hoàn tác được hiển thị cho người dùng
const undoWhy = computed(() => undoBlocker(l.value))
const canUndo = computed(() => !undoWhy.value)
const showUndoWhy = computed(() => !!undoWhy.value && !failed.value && !l.value.dry && !l.value.skipped && !isNotify.value && kind.value !== 'undo' && !l.value.undone && !!(l.value.before || l.value.after))
async function undo() {
  const x = l.value, isRule = kind.value === 'rule'
  let what = x.after && x.after.status !== undefined ? (x.before.status === 'ACTIVE' ? 'Camp sẽ được bật lại.' : 'Camp sẽ được tắt lại.') : `Ngân sách sẽ về ${fmt(x.before.dailyBudget)}.`
  if (isRule) what += ` Rule “${x.refName}” sẽ tạm không tác động lại camp này trong 24 giờ.`
  if (!await confirm('Hoàn tác thay đổi này?', `${x.target.name}: ${what}`, { ok: 'Hoàn tác' })) return
  try {
    await api(`logs/${x.id}/undo`, 'POST', {})
  } catch (e) {
    if (!(e.data && e.data.drift)) throw e
    if (!await confirm('Camp đã thay đổi kể từ lúc đó', `${e.message} Bạn vẫn muốn hoàn tác?`, { ok: 'Vẫn hoàn tác', danger: true })) return
    await api(`logs/${x.id}/undo`, 'POST', { force: true })
  }
  toast('Đã hoàn tác — xem dòng nhật ký mới')
  emit('retried'); emit('update:modelValue', false)
}
const undoneAt = computed(() => (l.value.undone ? new Date(l.value.undone.at).toLocaleString('vi-VN') : ''))
const canRetry = computed(() => failed.value && (kind.value === 'rule' ? !!ref_.value : kind.value === 'schedule' && !!ref_.value))
</script>

<template>
  <Modal :model-value="modelValue" title="Chi tiết nhật ký" width="720px" @update:model-value="emit('update:modelValue', $event)">
    <template v-if="log">
      <div class="head">
        <span class="ic" :class="status.tone"><component :is="status.icon" :size="22" /></span>
        <div class="hb">
          <h4>{{ l.source }}</h4>
          <p class="muted">{{ l.name }}</p>
        </div>
        <Badge v-if="l.undone" tone="neutral">Đã hoàn tác</Badge>
        <Badge :tone="status.tone" dot>{{ status.text }}</Badge>
      </div>

      <Callout v-if="l.skipped" tone="warning">{{ l.detail }} Đây chỉ là ghi nhận, tool không thay đổi gì.</Callout>
      <Callout v-if="l.undone" tone="info">Thay đổi này đã được hoàn tác lúc {{ undoneAt }}.</Callout>
      <Callout v-if="kind === 'undo'" tone="info">Đây là một lần hoàn tác thay đổi trước đó. Camp đã được đưa về giá trị cũ.</Callout>

      <dl class="meta">
        <div><dt>Thời gian</dt><dd class="cap">{{ when }}</dd></div>
        <div><dt>Nguồn</dt><dd>{{ KIND_LABEL[kind] }}<template v-if="l.refName"> · “{{ l.refName }}”</template>
          <a v-if="ref_" href="#" class="lk" @click.prevent="go(kind === 'rule' ? '/rules' : '/schedules')">Mở <ExternalLink :size="13" /></a>
          <span v-else-if="l.refId" class="faint"> (đã bị xoá)</span></dd></div>
        <div v-if="l.target"><dt>Đối tượng</dt><dd>{{ l.target.name || l.name }}<span v-if="l.target.level" class="faint"> · {{ l.target.level === 'adset' ? 'Nhóm quảng cáo' : 'Chiến dịch' }}</span><code v-if="l.target.id">{{ l.target.id }}</code></dd></div>
        <div v-if="actionText"><dt>Hành động</dt><dd>{{ actionText }}</dd></div>
        <div v-if="l.mode"><dt>Chế độ lúc chạy</dt><dd>{{ MODE_LABEL[l.mode] }}</dd></div>
        <div v-if="!failed"><dt>Kết quả</dt><dd>{{ l.detail }}</dd></div>
      </dl>

      <!-- Lỗi -->
      <template v-if="failed">
        <section class="box danger">
          <h5>Nguyên nhân</h5>
          <p>{{ (err && err.message) || l.detail }}</p>
        </section>
        <section class="box">
          <h5><Lightbulb :size="16" />Cách khắc phục</h5>
          <ul class="hints">
            <li v-for="(h, i) in hints" :key="i">
              <span v-html="rich(h.text)" />
              <button v-if="h.to" class="cta" @click="go(h.to)">{{ h.cta }} <ArrowRight :size="13" /></button>
            </li>
          </ul>
        </section>
      </template>

      <!-- Điều kiện của rule -->
      <section v-if="cond" class="box">
        <h5>Điều kiện rule đã khớp</h5>
        <p>{{ cond }}</p>
      </section>

      <!-- Trước / sau -->
      <section v-if="rows.length" class="box">
        <h5>{{ failed ? 'Thay đổi dự định' : dry ? 'Thay đổi sẽ thực hiện (chưa áp dụng vì Chạy thử)' : 'Thay đổi đã thực hiện' }}</h5>
        <table><thead><tr><th /><th>Trước</th><th /><th>Sau</th></tr></thead>
          <tbody><tr v-for="r in rows" :key="r[0]"><td class="k">{{ r[0] }}</td><td class="num">{{ r[1] }}</td><td class="ar"><ArrowRight :size="14" /></td><td class="num strong">{{ r[2] }}</td></tr></tbody></table>
      </section>

      <!-- Số liệu lúc đó -->
      <section v-if="metrics" class="box">
        <h5>Số liệu của mục này lúc đó (hôm nay)</h5>
        <div class="chips">
          <span><small>Chi tiêu</small><b class="num">{{ fmt(metrics.spend) }}</b></span>
          <span><small>Kết quả</small><b class="num">{{ metrics.results }}</b></span>
          <span><small>CPA</small><b class="num">{{ fmt(metrics.cpa) }}</b></span>
          <span><small>ROAS</small><b class="num">{{ metrics.roas == null ? '–' : fmtDec(metrics.roas) }}</b></span>
        </div>
      </section>

      <!-- Kỹ thuật -->
      <section v-if="techRows.length || request || (err && err.stack)" class="tech">
        <button class="tg" :aria-expanded="showTech" @click="showTech = !showTech">Chi tiết kỹ thuật <ChevronDown :size="16" :class="{ up: showTech }" /></button>
        <div v-if="showTech" class="tb">
          <dl v-if="techRows.length" class="kv">
            <div v-for="[k, v] in techRows" :key="k"><dt>{{ k }}</dt><dd><code>{{ v }}</code><button v-if="k === 'fbtrace_id'" class="mini" @click="copy(String(v), 'Đã sao chép fbtrace_id')"><Copy :size="12" /></button></dd></div>
          </dl>
          <div v-if="request" class="req">
            <h6>Yêu cầu đã gửi tới Facebook</h6>
            <pre>{{ request.method }} {{ request.path }}
{{ Object.keys(request.params || {}).length ? JSON.stringify(request.params, null, 2) : '(không có tham số)' }}</pre>
            <small class="faint">Token luôn được ẩn khỏi nhật ký.</small>
          </div>
          <div v-if="err && err.stack" class="req"><h6>Vết lỗi (stack)</h6><pre>{{ err.stack }}</pre></div>
        </div>
      </section>

      <p v-if="showUndoWhy" class="old faint">Không thể hoàn tác: {{ undoWhy }}</p>
      <p v-if="!hasDetail" class="old faint">Dòng nhật ký này được ghi trước khi tool lưu chi tiết nên chỉ có thông tin cơ bản ở trên.</p>
    </template>

    <template #footer>
      <Btn :icon="Copy" @click="copyAll">Sao chép chi tiết</Btn>
      <Btn v-if="canUndo" :icon="Undo2" :action="undo">Hoàn tác</Btn>
      <Btn v-if="canRetry" :icon="RotateCcw" :action="retry">{{ kind === 'rule' ? 'Kiểm tra lại rule' : 'Chạy lại lịch' }}</Btn>
      <Btn variant="primary" @click="emit('update:modelValue', false)">Đóng</Btn>
    </template>
  </Modal>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
.ic { width: 46px; height: 46px; border-radius: 16px; display: grid; place-items: center; flex: none; }
.ic.info { background: var(--info-soft); color: var(--info); }
.ic.success { background: var(--success-soft); color: var(--success); } .ic.warning { background: var(--warning-soft); color: var(--warning); } .ic.danger { background: var(--danger-soft); color: var(--danger); }
.hb { flex: 1; min-width: 0; } h4 { font-size: 17px; letter-spacing: -.02em; overflow-wrap: anywhere; } .hb p { font-size: 14px; overflow-wrap: anywhere; }
.meta { margin: 0 0 16px; border: 1px solid var(--border); border-radius: 14px; overflow: hidden; }
.meta > div { display: flex; gap: 14px; padding: 10px 16px; border-bottom: 1px solid var(--border); font-size: 14px; }
.meta > div:last-child { border: 0; }
dt { width: 130px; flex: none; color: var(--text-2); } dd { margin: 0; flex: 1; min-width: 0; overflow-wrap: anywhere; font-weight: 550; } .cap { text-transform: capitalize; }
dd code { margin-left: 8px; font-weight: 400; }
.lk { display: inline-flex; align-items: center; gap: 4px; margin-left: 8px; font-size: 13px; font-weight: 600; color: var(--accent); text-decoration: none; }
.box { margin-bottom: 14px; padding: 14px 16px; border-radius: 14px; background: var(--surface-2); border: 1px solid var(--border); }
.box.danger { background: var(--danger-soft); border-color: color-mix(in srgb, var(--danger) 30%, transparent); }
.box h5 { display: flex; align-items: center; gap: 7px; margin: 0 0 8px; font-size: 13px; font-weight: 700; color: var(--text-2); letter-spacing: .02em; }
.box.danger h5 { color: var(--danger); } .box p { margin: 0; font-size: 14.5px; line-height: 1.6; overflow-wrap: anywhere; }
.hints { margin: 0; padding: 0; list-style: none; display: grid; gap: 10px; }
.hints li { display: flex; gap: 10px; align-items: flex-start; justify-content: space-between; font-size: 14px; line-height: 1.55; color: var(--text-2); }
.hints li > span { flex: 1; min-width: 0; } .hints :deep(code) { font-size: .85em; }
.cta { flex: none; display: inline-flex; align-items: center; gap: 5px; padding: 5px 11px; border-radius: 9px; border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent); background: var(--accent-soft); color: var(--accent); font-weight: 600; font-size: 13px; white-space: nowrap; }
.cta:hover { background: var(--accent); color: #fff; }
table { width: 100%; border-collapse: collapse; } th { text-align: left; font-size: 12px; color: var(--text-3); font-weight: 600; padding: 0 0 6px; } td { padding: 6px 0; font-size: 14.5px; }
td.k { color: var(--text-2); width: 40%; } td.ar { width: 30px; color: var(--text-3); } td.strong { font-weight: 700; color: var(--accent); }
.chips { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; } .chips span { padding: 8px 12px; border-radius: 11px; background: var(--surface); border: 1px solid var(--border); }
.chips small { display: block; font-size: 12px; color: var(--text-3); } .chips b { font-size: 15.5px; }
.tech { margin: 4px 0 8px; } .tg { display: inline-flex; align-items: center; gap: 6px; border: 0; background: none; padding: 6px 2px; font-weight: 650; font-size: 13.5px; color: var(--text-2); }
.tg:hover { color: var(--accent); } .tg svg { transition: transform .25s var(--ease); } .tg svg.up { transform: rotate(180deg); }
.tb { padding: 12px 14px; border-radius: 14px; background: var(--surface-3); margin-top: 6px; }
.kv { margin: 0 0 12px; } .kv > div { display: flex; gap: 12px; padding: 5px 0; font-size: 13.5px; } .kv dt { width: 130px; } .kv dd { font-weight: 400; display: flex; gap: 6px; align-items: flex-start; }
.kv code { background: var(--surface); }
.mini { border: 0; background: var(--surface); border-radius: 6px; width: 22px; height: 22px; display: grid; place-items: center; color: var(--text-2); flex: none; }
.mini:hover { color: var(--accent); }
.req h6 { margin: 8px 0 6px; font-size: 12.5px; color: var(--text-2); }
pre { margin: 0 0 6px; padding: 10px 12px; border-radius: 10px; background: var(--surface); font: 12.5px/1.55 ui-monospace, 'Cascadia Code', Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow: auto; }
.old { font-size: 13.5px; text-align: center; margin: 8px 0 0; }
@media (max-width: 640px) { .meta > div { flex-direction: column; gap: 2px; } dt { width: auto; } .chips { grid-template-columns: repeat(2, 1fr); } .kv > div { flex-direction: column; gap: 0; } }
</style>
