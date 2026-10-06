<script setup>
// Báo cáo lên hệ thống nội bộ của công ty: tài khoản đăng nhập, các mốc, chế độ gửi, và Team ↔ tài khoản/chiến dịch Facebook.
import { reactive, ref, computed, onMounted } from 'vue'
import { Save, PlugZap, Plus, Trash2, CheckCircle2, ClipboardList, Building2 } from 'lucide-vue-next'
import { state, ensureObjs } from '../../stores/app'
import { toast } from '../../stores/ui'
import { api } from '../../lib/api'
import { fmt } from '../../lib/format'
import { SLOTS, SLOT_LABEL, MODE_LABEL, DEFAULT_BASE_URL, MAX_TEAMS, validateCompanyConfig, teamCampaigns, sumMetrics, overlaps } from '../../lib/companyReport'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'
import Badge from '../../components/Badge.vue'
import Switch from '../../components/Switch.vue'
import Callout from '../../components/Callout.vue'
import Segmented from '../../components/Segmented.vue'

const loaded = ref(false)
const cfg = ref({})
const f = reactive({ enabled: false, mode: 'approve', slots: [], leadMin: 0, email: '', password: '', baseUrl: DEFAULT_BASE_URL, teams: [] })
const remoteTeams = ref(null) // Team của hệ thống công ty (sau khi Kiểm tra kết nối)
const me = ref(null)
const errors = ref({})

onMounted(async () => {
  ensureObjs()
  const r = await api('company')
  cfg.value = r.config
  Object.assign(f, { enabled: r.config.enabled, mode: r.config.mode, slots: [...r.config.slots], leadMin: r.config.leadMin || 0, email: r.config.email, password: '', baseUrl: r.config.baseUrl, teams: r.config.teams.map((t) => ({ ...t, accountIds: [...t.accountIds] })) })
  loaded.value = true
})

const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const camps = computed(() => state.objs.filter((o) => o.level === 'campaign'))
// Xem trước số hôm nay của từng Team (tính ngay trên trình duyệt, cùng luật với server)
const preview = (t) => { const list = teamCampaigns(t, camps.value); return { n: list.length, m: sumMetrics(list, Object.fromEntries(list.map((o) => [o.id, o.metrics]))) } }
const dup = computed(() => overlaps(f.teams, camps.value))

// Danh sách Team để chọn: Team công ty đã tải, cộng các Team đã lưu (khi chưa tải lại)
const teamOptions = computed(() => {
  const list = (remoteTeams.value || []).filter((t) => t.status !== 'ARCHIVED')
  for (const t of f.teams) if (t.id && !list.some((x) => x.id === t.id)) list.push({ id: t.id, code: t.code, name: t.name })
  return list
})
function pickTeam(row, id) {
  const t = teamOptions.value.find((x) => x.id === id)
  Object.assign(row, { id, code: t ? t.code : '', name: t ? t.name : '' })
}
const addTeam = () => f.teams.push({ id: '', code: '', name: '', accountIds: accounts.value.length === 1 ? [accounts.value[0].id] : [], match: '' })
const toggle = (arr, v) => { const i = arr.indexOf(v); if (i >= 0) arr.splice(i, 1); else arr.push(v) }

const body = () => ({ enabled: f.enabled, mode: f.mode, slots: f.slots, leadMin: f.leadMin, email: f.email.trim(), password: f.password, baseUrl: f.baseUrl.trim(), teams: f.teams })

async function save(silent = false) {
  const v = validateCompanyConfig(body(), { ...cfg.value })
  errors.value = v.errors
  if (!v.ok) { toast(v.first, 'error'); return false }
  const r = await api('company/config', 'POST', body())
  cfg.value = r.config
  f.password = ''
  if (!silent) toast('Đã lưu cài đặt báo cáo công ty')
  return true
}
async function test() {
  if (!f.email.trim() || (!f.password && !cfg.value.has_password)) { toast('Nhập email và mật khẩu hệ thống công ty trước', 'error'); return }
  // lưu tài khoản trước (chưa bật cũng được) để server đăng nhập bằng đúng tài khoản đang nhập
  const r0 = await api('company/config', 'POST', { email: f.email.trim(), password: f.password, baseUrl: f.baseUrl.trim() })
  cfg.value = r0.config
  f.password = ''
  me.value = null
  const r = await api('company/test', 'POST')
  me.value = r.user
  remoteTeams.value = r.teams
  toast(`Đăng nhập được: ${r.user.name || r.user.email}`)
  if (!f.teams.length && r.teams.length === 1) { addTeam(); pickTeam(f.teams[0], r.teams[0].id) }
}
const MODE_TEXT = {
  preview: 'Chỉ tạo bản báo cáo và nhắn Telegram để bạn so số. Tool không gửi gì lên hệ thống công ty.',
  approve: 'Tool chỉ gửi khi bạn bấm Gửi ở trang Báo cáo công ty.',
  auto: 'Đến mốc tool gửi luôn rồi nhắn Telegram. Số trông bất thường (có đơn mà doanh thu bằng 0, Team không khớp chiến dịch, không lấy được số Facebook) thì dừng lại để bạn xem và gửi tay. Hệ thống công ty lỗi thì tự thử lại tối đa 3 lần.',
}
const leadHint = computed(() => {
  const n = Number(f.leadMin) || 0
  if (!n) return 'Để 0 thì tool làm báo cáo đúng giờ mốc.'
  const at = (f.slots.length ? f.slots : SLOTS).map((s) => { const m = s * 60 - n; return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}` })
  return `Tool lấy số và ${f.mode === 'auto' ? 'gửi' : 'nhắn Telegram'} lúc ${at.join(', ')}. Báo cáo vẫn ghi đúng mốc.`
})
const modes = Object.entries(MODE_LABEL).map(([value, label]) => ({ value, label }))
</script>

<template>
  <div v-if="loaded" class="wrap">
    <section class="card pad">
      <div class="top">
        <div>
          <h3>Báo cáo lên hệ thống công ty</h3>
          <p class="muted sub">Đến mỗi mốc, tool cộng số Facebook của từng Team (chi tiêu, tin nhắn, SĐT = khách hàng tiềm năng, Đơn = kết quả, DSO sau VAT = doanh thu, hiển thị, nhấp) thành bản báo cáo. Đơn và DSO tính theo <b>Loại kết quả</b> ở <RouterLink to="/settings/general">Cài đặt → Chung</RouterLink>. Tool nhắn Telegram khi có bản báo cáo mới; xem, sửa và gửi ở trang <RouterLink to="/company">Báo cáo công ty</RouterLink>.</p>
        </div>
        <Switch v-model="f.enabled" label="Bật báo cáo theo mốc" />
      </div>

      <h4 class="sec"><Building2 :size="16" /> Tài khoản hệ thống công ty</h4>
      <div class="grid">
        <Field label="Email đăng nhập" :error="errors.email"><input v-model="f.email" class="input" type="email" autocomplete="off" placeholder="ban@congty.vn" /></Field>
        <Field label="Mật khẩu" :error="errors.password"><template #aside><Badge v-if="cfg.has_password" tone="success">đã lưu</Badge></template>
          <input v-model="f.password" class="input" type="password" autocomplete="new-password" :placeholder="cfg.has_password ? 'Để trống = giữ mật khẩu cũ' : 'Mật khẩu web công ty'" /></Field>
      </div>
      <details class="adv"><summary>Địa chỉ hệ thống</summary>
        <Field :error="errors.baseUrl" hint="Chỉ đổi khi công ty chuyển sang tên miền khác."><input v-model="f.baseUrl" class="input" :placeholder="DEFAULT_BASE_URL" /></Field>
      </details>
      <div class="btns">
        <Btn :icon="PlugZap" :action="test">Kiểm tra kết nối</Btn>
        <p v-if="me" class="ok"><CheckCircle2 :size="16" /> Đã đăng nhập: <b>{{ me.name || me.email }}</b><span v-if="remoteTeams"> · {{ remoteTeams.length }} Team</span></p>
      </div>
      <p class="muted tiny">Mật khẩu được lưu như token Facebook: không hiện lại trên giao diện, được mã hoá nếu lưu dữ liệu ở Upstash.</p>
    </section>

    <section class="card pad">
      <h4 class="sec">Mốc báo cáo</h4>
      <div class="slots">
        <button v-for="s in SLOTS" :key="s" type="button" class="slot" :class="{ on: f.slots.includes(s) }" :aria-pressed="f.slots.includes(s)" @click="toggle(f.slots, s)">{{ SLOT_LABEL[s] }}</button>
      </div>
      <p v-if="errors.slots" class="err">{{ errors.slots }}</p>
      <p class="muted tiny">Mốc 9h ghi số cả ngày hôm qua vào báo cáo 9h của ngày hôm qua trên công ty (đã có thì cập nhật, chưa có thì tạo mới); 12h, 17h, 22h là số lũy kế hôm nay. Facebook cập nhật số chậm khoảng 15–30 phút.</p>
      <Field label="Làm báo cáo trước mốc (phút)" :error="errors.leadMin" class="lead" :hint="leadHint">
        <input v-model.number="f.leadMin" class="input" type="number" min="0" max="60" step="5" inputmode="numeric" />
      </Field>

      <h4 class="sec">Chế độ gửi</h4>
      <Segmented v-model="f.mode" :options="modes" />
      <p class="muted tiny mode">{{ MODE_TEXT[f.mode] }} Trước khi gửi, tool luôn kiểm tra mốc đó đã có báo cáo trên hệ thống công ty chưa, có rồi thì không gửi đè.</p>
    </section>

    <section class="card pad">
      <h4 class="sec"><ClipboardList :size="16" /> Team</h4>
      <p class="muted sub2">Mỗi Team lấy số của các chiến dịch thuộc tài khoản quảng cáo đã chọn, và (nếu có nhập) tên chứa một trong các từ khoá.</p>
      <Callout v-if="!remoteTeams && !f.teams.length" tone="info">Bấm <b>Kiểm tra kết nối</b> ở trên để tải danh sách Team từ hệ thống công ty.</Callout>
      <div v-for="(t, i) in f.teams" :key="i" class="team">
        <div class="trow">
          <Field label="Team trên hệ thống công ty" class="tsel">
            <select class="input" :value="t.id" @change="pickTeam(t, $event.target.value)">
              <option value="" disabled>Chọn Team…</option>
              <option v-for="o in teamOptions" :key="o.id" :value="o.id">{{ [o.code, o.name].filter(Boolean).join(' · ') }}</option>
            </select>
          </Field>
          <Btn size="sm" variant="ghost danger" :icon="Trash2" aria-label="Bỏ Team này" @click="f.teams.splice(i, 1)" />
        </div>
        <Field v-if="accounts.length" label="Tài khoản quảng cáo">
          <div class="accs">
            <button v-for="a in accounts" :key="a.id" type="button" class="acc" :class="{ on: t.accountIds.includes(a.id) }" :aria-pressed="t.accountIds.includes(a.id)" @click="toggle(t.accountIds, a.id)">{{ a.name || a.id }}</button>
          </div>
        </Field>
        <Field label="Tên chiến dịch chứa (không bắt buộc)" hint="Nhiều từ khoá cách nhau bằng dấu phẩy, không phân biệt hoa thường. Vd: CT01, hoạt huyết">
          <input v-model="t.match" class="input" placeholder="Để trống = mọi chiến dịch của tài khoản đã chọn" />
        </Field>
        <p class="pv">
          <template v-if="preview(t).n">Hôm nay: <b>{{ preview(t).n }}</b> chiến dịch · chi <b class="num">{{ fmt(preview(t).m.spend) }}</b> · {{ fmt(preview(t).m.messages) }} tin nhắn · {{ fmt(preview(t).m.phones) }} SĐT · {{ fmt(preview(t).m.orders) }} đơn · DSO <b class="num">{{ fmt(preview(t).m.dso_after) }}</b></template>
          <template v-else-if="t.accountIds.length || t.match">Chưa khớp chiến dịch nào.</template>
          <template v-else>Chọn tài khoản hoặc nhập từ khoá.</template>
        </p>
      </div>
      <Callout v-if="dup.length" tone="warning">{{ dup.length }} chiến dịch nằm trong nhiều Team nên số sẽ bị cộng hai lần, vd “{{ dup[0].name }}” ({{ dup[0].teams.join(', ') }}).</Callout>
      <p v-if="errors.teams" class="err">{{ errors.teams }}</p>
      <Btn v-if="f.teams.length < MAX_TEAMS" size="sm" :icon="Plus" @click="addTeam">Thêm Team</Btn>
    </section>

    <div class="save"><Btn variant="primary" :icon="Save" :action="() => save()">Lưu</Btn></div>
  </div>
</template>

<style scoped>
.wrap { display: grid; gap: 16px; }
.top { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 18px; font-size: 14.5px; line-height: 1.6; }
.sub2 { margin: -4px 0 14px; font-size: 14px; }
.sec { display: flex; align-items: center; gap: 8px; font-size: 15px; margin: 6px 0 12px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
.adv { margin: 0 0 14px; font-size: 14px; } .adv summary { cursor: pointer; color: var(--text-2); font-weight: 600; margin-bottom: 8px; }
.btns { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.ok { display: flex; align-items: center; gap: 6px; color: var(--success); font-size: 14px; margin: 0; }
.lead { margin-top: 16px; max-width: 360px; }
.tiny { font-size: 13px; margin: 10px 0 0; line-height: 1.55; } .mode { margin-top: 10px; }
.slots, .accs { display: flex; gap: 8px; flex-wrap: wrap; }
.slot, .acc { border: 1px solid var(--border-strong); background: var(--surface); padding: 7px 14px; border-radius: 99px; font-weight: 600; font-size: 13.5px; color: var(--text-2); transition: .15s var(--ease); }
.slot.on, .acc.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.slots + .err, .err { color: var(--danger); font-size: 13.5px; margin: 8px 0 0; }
.sec + .slots { margin-bottom: 2px; }
.card .sec:not(:first-child) { margin-top: 22px; }
.team { padding: 16px; border: 1px solid var(--border); border-radius: 14px; background: var(--surface-2); margin-bottom: 12px; }
.trow { display: flex; align-items: flex-end; gap: 10px; } .tsel { flex: 1; min-width: 0; }
.trow :deep(.btn) { margin-bottom: 14px; }
.pv { margin: 0; font-size: 13.5px; color: var(--text-2); }
.save { display: flex; justify-content: flex-end; }
@media (max-width: 700px) { .grid { grid-template-columns: 1fr; } .top { flex-direction: column-reverse; gap: 6px; } }
</style>
