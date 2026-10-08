<script setup>
// Cài đặt → Thông báo: các kênh nhận thông báo (Telegram, Gmail…) và giờ gửi báo cáo.
// Form "Thêm kênh" vẽ theo mô tả server gửi về (GET /api/notify: types[].fields), nên server thêm loại kênh mới
// thì màn hình này tự có, không phải sửa.
import { reactive, ref, computed, onMounted } from 'vue'
import { Send, Mail, Bell, Plus, Pencil, Trash2, Save, FileText, CalendarDays, CheckCircle2, XCircle, X } from 'lucide-vue-next'
import { state, saveSettings, isOwner } from '../../stores/app'
import { toast, confirm } from '../../stores/ui'
import { api } from '../../lib/api'
import { isTime } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'
import Badge from '../../components/Badge.vue'
import Switch from '../../components/Switch.vue'
import Segmented from '../../components/Segmented.vue'
import Callout from '../../components/Callout.vue'

const ICONS = { telegram: Send, email: Mail }
const iconOf = (type) => ICONS[type] || Bell

const types = ref([])    // loại kênh có trong tool: [{ type, label, fields, help }]
const topics = ref([])   // loại tin: [{ key, label }]
const channels = ref([]) // kênh đã cài
const loaded = ref(false)
const results = reactive({}) // kết quả gửi gần nhất theo kênh / nút báo cáo: [{ id, ok, error? }]

async function load() {
  const r = await api('notify')
  types.value = r.types
  topics.value = r.topics
  channels.value = r.channels
  loaded.value = true
  state.settings.has_notify = r.channels.some((c) => c.enabled)
}
onMounted(load)

const typeOf = (type) => types.value.find((t) => t.type === type) || { type, label: type, fields: [], help: '', defaultTopics: [] }
const topicLabel = (k) => topics.value.find((t) => t.key === k)?.label || k
// Dòng tóm tắt người nhận: các ô không bí mật (Chat ID, email người nhận…)
const summary = (c) => typeOf(c.type).fields.filter((f) => !f.secret && c.config[f.key]).map((f) => c.config[f.key]).join(' · ')

// ------------------------------------------------------------ Thêm / sửa kênh
const editing = ref(null) // null = đóng form; { id?, type, name, topics, config }
const errors = ref({})

function startAdd() {
  const first = types.value[0]
  editing.value = { id: null, type: first.type, name: '', topics: [...first.defaultTopics], config: emptyConfig(first) }
  errors.value = {}
}
function startEdit(c) {
  editing.value = { id: c.id, type: c.type, name: c.name, topics: [...c.topics], config: { ...c.config }, savedSecrets: c.savedSecrets }
  errors.value = {}
}
function emptyConfig(t) { return Object.fromEntries(t.fields.map((f) => [f.key, ''])) }
function pickType(type) {
  editing.value.type = type
  editing.value.config = emptyConfig(typeOf(type))
  editing.value.topics = [...typeOf(type).defaultTopics] // vd Gmail mặc định không nhận Nhật ký tự động (giới hạn thư/ngày)
  errors.value = {}
}
function toggleTopic(k) {
  const list = editing.value.topics
  editing.value.topics = list.includes(k) ? list.filter((x) => x !== k) : [...list, k]
}
const isSaved = (key) => !!editing.value?.savedSecrets?.includes(key)

async function saveChannel() {
  const e = editing.value
  const body = { type: e.type, name: e.name, topics: e.topics, config: e.config }
  try {
    await api(e.id ? `notify/channels/${e.id}` : 'notify/channels', 'POST', body)
  } catch (err) {
    if (err.fields) { errors.value = err.fields; toast('Hãy sửa các mục báo lỗi trước khi lưu', 'error'); return }
    throw err
  }
  toast(e.id ? 'Đã lưu kênh' : 'Đã thêm kênh. Bấm "Gửi thử" để kiểm tra.')
  editing.value = null
  await load()
}

async function setEnabled(c, on) {
  await api(`notify/channels/${c.id}`, 'POST', { enabled: on })
  await load()
}

async function remove(c) {
  if (!(await confirm('Xoá kênh này?', `${c.name || c.label} sẽ không nhận thông báo nữa.`, { ok: 'Xoá', danger: true }))) return
  await api(`notify/channels/${c.id}`, 'DELETE')
  delete results[c.id]
  toast('Đã xoá kênh')
  await load()
}

// Gửi thử / gửi báo cáo: hiện kết quả từng người nhận. Không ai nhận được thì server trả 400 kèm lý do từng người.
async function run(key, path, okText) {
  delete results[key]
  try {
    const r = await api(path, 'POST')
    results[key] = r.results || []
    const bad = results[key].filter((x) => !x.ok)
    if (bad.length) toast(`Đã gửi cho ${r.sent}/${r.total} người nhận, ${bad.length} người lỗi (xem bên dưới)`, 'error')
    else toast(okText)
  } catch (e) {
    if (e.data && e.data.results) results[key] = e.data.results
    throw e
  }
}
const test = (c) => run(c.id, `notify/channels/${c.id}/test`, 'Đã gửi tin thử, kiểm tra nơi nhận')

// ------------------------------------------------------------ Báo cáo
const rep = reactive({ reportTime: state.settings.reportTime || '08:00', weekly: state.settings.weeklyReport !== false })
const timeError = computed(() => (rep.reportTime && !isTime(rep.reportTime) ? 'Giờ báo cáo không hợp lệ' : ''))
async function saveReport() {
  if (timeError.value) { toast(timeError.value, 'error'); return }
  await saveSettings({ reportTime: rep.reportTime, weeklyReport: rep.weekly })
  toast('Đã lưu cài đặt báo cáo')
}
const reportsTo = computed(() => channels.value.filter((c) => c.enabled && c.topics.includes('REPORT')).length)
</script>

<template>
  <div>
  <section class="card pad">
    <div class="head">
      <div>
        <h3>Kênh thông báo</h3>
        <p class="muted sub">Nơi tool gửi tin khi lịch, rule thay đổi camp, khi có cảnh báo, và gửi báo cáo. Mỗi kênh chọn được loại tin muốn nhận. Không bắt buộc.</p>
      </div>
      <Btn v-if="isOwner() && loaded && !editing" variant="primary" :icon="Plus" @click="startAdd">Thêm kênh</Btn>
    </div>

    <Callout v-if="loaded && !channels.length && !editing" tone="info">Chưa có kênh nào: thông báo chỉ được ghi vào Nhật ký.</Callout>

    <ul class="list">
      <li v-for="c in channels" :key="c.id" class="ch" :class="{ off: !c.enabled }">
        <div class="row">
          <span class="ic"><component :is="iconOf(c.type)" :size="19" /></span>
          <div class="info">
            <b>{{ c.name || c.label }}</b> <span v-if="c.name" class="muted type">{{ c.label }}</span>
            <p class="muted rcpt">{{ summary(c) || 'Chưa có người nhận' }}</p>
            <div class="tags"><Badge v-for="t in c.topics" :key="t">{{ topicLabel(t) }}</Badge></div>
          </div>
          <Switch :model-value="c.enabled" :disabled="!isOwner()" label="Bật kênh" @update:model-value="(v) => setEnabled(c, v)" />
        </div>
        <div class="btns">
          <Btn size="sm" :icon="Send" :action="() => test(c)">Gửi thử</Btn>
          <template v-if="isOwner()">
            <Btn size="sm" variant="ghost" :icon="Pencil" @click="startEdit(c)">Sửa</Btn>
            <Btn size="sm" variant="ghost" :icon="Trash2" :action="() => remove(c)">Xoá</Btn>
          </template>
        </div>
        <ul v-if="results[c.id]" class="res" aria-live="polite">
          <li v-for="r in results[c.id]" :key="r.id" :class="r.ok ? 'ok' : 'no'">
            <CheckCircle2 v-if="r.ok" :size="17" /><XCircle v-else :size="17" />
            <b>{{ r.id }}</b><span>{{ r.ok ? 'Đã gửi' : r.error }}</span>
          </li>
        </ul>
      </li>
    </ul>

    <form v-if="editing" class="edit" @submit.prevent>
      <div class="edit-head">
        <h4>{{ editing.id ? 'Sửa kênh' : 'Thêm kênh' }}</h4>
        <button type="button" class="x" aria-label="Đóng" @click="editing = null"><X :size="18" /></button>
      </div>
      <Field v-if="!editing.id && types.length > 1" label="Loại kênh" :error="errors.type">
        <Segmented :model-value="editing.type" :options="types.map((t) => ({ value: t.type, label: t.label, icon: iconOf(t.type) }))" @update:model-value="pickType" />
      </Field>
      <Field label="Tên kênh" hint="Không bắt buộc, vd “Nhóm team A”, “Email sếp”." :error="errors.name">
        <input v-model="editing.name" class="input" maxlength="100" :placeholder="typeOf(editing.type).label" />
      </Field>
      <Field v-for="f in typeOf(editing.type).fields" :key="f.key" :label="f.label" :hint="f.hint" :error="errors['config.' + f.key]">
        <template v-if="f.secret && isSaved(f.key)" #aside><Badge tone="success">đã lưu</Badge></template>
        <input v-model="editing.config[f.key]" class="input" :type="f.secret ? 'password' : 'text'" autocomplete="off"
          :placeholder="f.secret && isSaved(f.key) ? 'Để trống = giữ giá trị cũ' : f.placeholder" />
      </Field>
      <Field label="Nhận loại tin" :error="errors.topics">
        <div class="topics">
          <label v-for="t in topics" :key="t.key" class="topic"><input type="checkbox" :checked="editing.topics.includes(t.key)" @change="toggleTopic(t.key)" />{{ t.label }}</label>
        </div>
      </Field>
      <details v-if="typeOf(editing.type).help">
        <summary>Cách lấy thông tin cho {{ typeOf(editing.type).label }}</summary>
        <!-- hướng dẫn cố định viết trong code server (NotifyChannel.help), không phải dữ liệu người dùng nhập -->
        <div class="help" v-html="typeOf(editing.type).help" />
      </details>
      <div class="btns">
        <Btn variant="primary" :icon="Save" :action="saveChannel">{{ editing.id ? 'Lưu' : 'Thêm kênh' }}</Btn>
        <Btn variant="ghost" @click="editing = null">Huỷ</Btn>
      </div>
    </form>
  </section>

  <section class="card pad">
    <h3>Báo cáo</h3>
    <p class="muted sub">Gửi tới các kênh có nhận loại tin <b>Báo cáo</b> ({{ reportsTo }} kênh).</p>
    <div class="grid">
      <Field label="Báo cáo hằng ngày lúc" :error="timeError"><input v-model="rep.reportTime" class="input" type="time" /></Field>
    </div>
    <div class="cmd">
      <span class="ic"><CalendarDays :size="20" /></span>
      <div class="cb">
        <h4>Báo cáo tuần</h4>
        <p class="muted">Sáng thứ Hai, cùng giờ với báo cáo hằng ngày: chi tiêu, kết quả, CPA của tuần trước so với tuần liền trước, 3 camp tốt nhất, 3 camp cần xem lại và số lần tool đã tự bật/tắt/đổi ngân sách.</p>
      </div>
      <Switch v-model="rep.weekly" label="Báo cáo tuần" />
    </div>
    <div class="btns">
      <Btn variant="primary" :icon="Save" :action="saveReport">Lưu</Btn>
      <Btn :icon="FileText" :action="() => run('report', 'report', 'Đã gửi báo cáo')">Gửi báo cáo ngay</Btn>
      <Btn :icon="CalendarDays" :action="() => run('report', 'report/weekly', 'Đã gửi báo cáo tuần')">Gửi báo cáo tuần</Btn>
    </div>
    <ul v-if="results.report" class="res" aria-live="polite">
      <li v-for="r in results.report" :key="r.id" :class="r.ok ? 'ok' : 'no'">
        <CheckCircle2 v-if="r.ok" :size="17" /><XCircle v-else :size="17" />
        <b>{{ r.id }}</b><span>{{ r.ok ? 'Đã gửi' : r.error }}</span>
      </li>
    </ul>
  </section>
  </div>
</template>

<style scoped>
.card + .card { margin-top: 20px; }
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.head { display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; }
.list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.ch { margin: 0; padding: 14px 16px; border: 1px solid var(--border); border-radius: 14px; }
.ch.off { opacity: .65; }
.row { display: flex; gap: 14px; align-items: flex-start; }
.ic { width: 42px; height: 42px; border-radius: 14px; display: grid; place-items: center; flex: none; background: var(--accent-soft); color: var(--accent); }
.info { flex: 1; min-width: 0; }
.info b { font-size: 15px; } .type { font-size: 13px; margin-left: 4px; }
.rcpt { margin: 2px 0 8px; font-size: 13.5px; overflow-wrap: anywhere; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.btns { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.edit { margin-top: 16px; padding: 18px; border: 1px solid var(--accent); border-radius: 14px; background: var(--surface-2); }
.edit-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.edit-head h4 { font-size: 16px; }
.x { border: 0; background: none; color: var(--text-2); padding: 4px; border-radius: 8px; }
.x:hover { background: var(--surface-3); }
.topics { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.topic { display: inline-flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 500; cursor: pointer; }
.topic input { width: 16px; height: 16px; accent-color: var(--accent); }
details { margin-top: 4px; padding: 12px 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); }
summary { cursor: pointer; font-weight: 600; font-size: 14px; }
.help { font-size: 14.5px; } .help :deep(ol) { margin: 10px 0 0; padding-left: 20px; } .help :deep(li) { margin-bottom: 6px; }
.grid { display: grid; grid-template-columns: 190px; gap: 0 14px; }
.cmd { display: flex; gap: 16px; align-items: flex-start; padding: 18px 0; margin-bottom: 4px; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.cb { flex: 1; min-width: 0; } .cb h4 { font-size: 15.5px; margin-bottom: 4px; } .cb p { font-size: 14px; line-height: 1.6; margin: 0; }
.res { list-style: none; margin: 14px 0 0; padding: 0; display: grid; gap: 6px; }
.res li { margin: 0; display: flex; align-items: flex-start; gap: 9px; padding: 9px 12px; border-radius: 10px; font-size: 14px; flex-wrap: wrap; }
.res li span { flex: 1 1 200px; min-width: 0; }
.res li svg { flex: none; margin-top: 1px; }
.res .ok { background: var(--success-soft); color: var(--success); }
.res .no { background: var(--danger-soft); color: var(--danger); }
@media (max-width: 800px) { .head { flex-direction: column; } .grid { grid-template-columns: 1fr; } }
</style>
