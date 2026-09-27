<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { Save, ShieldAlert, Gauge, GraduationCap } from 'lucide-vue-next'
import { state, saveSettings, ensureObjs } from '../../stores/app'
import { toast } from '../../stores/ui'
import { validateSettings } from '../../lib/validate'
import { fmt } from '../../lib/format'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'
import Switch from '../../components/Switch.vue'
import InfoTip from '../../components/InfoTip.vue'
import Callout from '../../components/Callout.vue'
import Segmented from '../../components/Segmented.vue'

const s = state.settings
const f = reactive({
  skipLearning: s.skipLearning !== false,
  dailyChangeCapPct: s.dailyChangeCapPct || 30,
  killSwitchEnabled: !!s.killSwitchEnabled,
  dailySpendLimit: s.dailySpendLimit || '',
  killScope: s.killScope === 'account' ? 'account' : 'total', // tổng mọi tài khoản (như trước) hay từng tài khoản
})
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
const mixedCur = computed(() => new Set(accounts.value.map((a) => a.currency).filter(Boolean)).size > 1)
const scopes = [{ value: 'total', label: 'Tổng mọi tài khoản' }, { value: 'account', label: 'Từng tài khoản' }]
// Từng tài khoản: mức riêng (Cài đặt → Mục tiêu) hoặc mức chung; số đã chi hôm nay để so
const ownLimit = (id) => Number(((state.settings.accountTargets || {})[id] || {}).dailySpendLimit) || 0
const accMeters = computed(() => accounts.value.map((a) => {
  const limit = ownLimit(a.id) || Number(f.dailySpendLimit) || 0
  const spend = state.objs.filter((o) => o.level === 'campaign' && o.accountId === a.id).reduce((t, o) => t + o.metrics.spend, 0)
  return { ...a, limit, spend, own: !!ownLimit(a.id), pct: limit > 0 ? Math.min(100, (spend / limit) * 100) : 0 }
}))
const noLimitAnywhere = computed(() => f.killScope === 'account' && accMeters.value.length > 0 && accMeters.value.every((m) => !m.limit))
const submitted = ref(false)
const touched = reactive({})
onMounted(() => ensureObjs())

// Cùng luật với server
const check = computed(() => validateSettings({ ...f, dailySpendLimit: f.dailySpendLimit === '' ? 0 : f.dailySpendLimit }, s))
const show = (k) => (submitted.value || touched[k] ? check.value.errors[k] : '')

// Chi tiêu hôm nay so với mức dừng khẩn (nếu đã có số liệu)
const spendToday = computed(() => state.objs.filter((o) => o.level === 'campaign').reduce((t, o) => t + o.metrics.spend, 0))
const pct = computed(() => { const l = Number(f.dailySpendLimit) || 0; return l > 0 ? Math.min(100, (spendToday.value / l) * 100) : 0 })
const hasData = computed(() => state.objsLoaded && state.objs.length > 0)

async function save() {
  submitted.value = true
  if (!check.value.ok) { toast('Hãy sửa các mục báo lỗi trước khi lưu', 'error'); return }
  await saveSettings(check.value.value)
  toast('Đã lưu cài đặt bảo vệ ngân sách')
}
</script>

<template>
  <section class="card pad">
    <h3>Bảo vệ ngân sách</h3>
    <p class="muted sub">Các “phanh an toàn” giúp rule và lịch không làm ngân sách của bạn đi quá xa, kể cả khi bạn không online.</p>

    <div class="row">
      <span class="ic"><GraduationCap :size="20" /></span>
      <div class="rb">
        <h4>Bỏ qua camp đang trong giai đoạn học <InfoTip tip="skipLearning" /></h4>
        <p class="muted">Khi rule muốn tăng/giảm ngân sách một camp đang học, tool sẽ bỏ qua và ghi vào Nhật ký. Rule tắt camp và lịch không bị ảnh hưởng.</p>
      </div>
      <Switch v-model="f.skipLearning" label="Bỏ qua camp đang học" />
    </div>

    <div class="row">
      <span class="ic"><Gauge :size="20" /></span>
      <div class="rb">
        <h4>Giới hạn thay đổi ngân sách mỗi ngày <InfoTip tip="dailyCap" /></h4>
        <p class="muted">Tổng phần trăm ngân sách một camp được phép thay đổi trong ngày do <b>rule</b> (tính trên ngân sách đầu ngày). Lịch do bạn đặt không bị giới hạn.</p>
        <Field :error="show('dailyChangeCapPct')">
          <div class="inl"><input v-model="f.dailyChangeCapPct" class="input num" type="number" min="5" max="100" step="1" @blur="touched.dailyChangeCapPct = true" /><span class="muted">% mỗi ngày (5–100)</span></div>
        </Field>
      </div>
    </div>

    <div class="row last">
      <span class="ic danger"><ShieldAlert :size="20" /></span>
      <div class="rb">
        <h4>Dừng khẩn khi chi tiêu vượt mức <InfoTip tip="killSwitch" /></h4>
        <p class="muted">Khi chi tiêu hôm nay đạt mức này, tool tự tắt các camp đang chạy (mỗi ngày tối đa một lần) và báo qua Telegram. Kiểm tra theo chu kỳ rule ở Cài đặt → Chung.</p>
        <div v-if="multiAcc" class="scope">
          <Segmented v-model="f.killScope" :options="scopes" size="sm" />
          <p class="muted">
            <template v-if="f.killScope === 'total'">Cộng chi tiêu của <b>mọi tài khoản</b>, vượt mức thì tắt <b>tất cả</b> camp đang chạy.</template>
            <template v-else>Mỗi tài khoản có mức riêng (đặt ở <RouterLink to="/settings/targets">Cài đặt → Mục tiêu</RouterLink>), chưa đặt thì dùng mức chung bên dưới. Tài khoản nào vượt mức thì chỉ tắt camp của tài khoản đó, các tài khoản khác không bị ảnh hưởng.</template>
          </p>
        </div>
        <Callout v-if="multiAcc && mixedCur && f.killScope === 'total'" tone="warning">Các tài khoản dùng <b>nhiều loại tiền</b> khác nhau nên tổng chi tiêu không có nghĩa. Nên chọn “Từng tài khoản”.</Callout>
        <Callout v-if="noLimitAnywhere" tone="warning">Chưa có mức nào (không có mức chung, không tài khoản nào có mức riêng) nên dừng khẩn sẽ không làm gì.</Callout>
        <Field :label="f.killScope === 'account' ? 'Mức chung cho mỗi tài khoản chưa đặt mức riêng' : 'Mức chi tiêu tối đa mỗi ngày'" :error="show('dailySpendLimit')">
          <input v-model="f.dailySpendLimit" class="input num lim" type="number" min="0" step="10000" placeholder="Ví dụ 3000000" @blur="touched.dailySpendLimit = true" />
        </Field>
        <div v-if="hasData && f.killScope === 'account' && multiAcc" class="ameters">
          <div v-for="m in accMeters" :key="m.id" class="am">
            <div class="ah"><b>{{ m.name }}</b><small class="faint">{{ m.limit ? (m.own ? 'mức riêng' : 'mức chung') : 'chưa có mức' }}</small></div>
            <div v-if="m.limit" class="bar"><i :style="{ width: m.pct + '%' }" :class="{ hot: m.pct >= 80 }" /></div>
            <small class="muted">Hôm nay đã chi <b class="num">{{ fmt(m.spend) }}</b><template v-if="m.limit"> / {{ fmt(m.limit) }} ({{ Math.round(m.pct) }}%)</template> {{ m.currency }}</small>
          </div>
        </div>
        <div v-else-if="hasData && Number(f.dailySpendLimit) > 0 && f.killScope === 'total'" class="meter">
          <div class="bar"><i :style="{ width: pct + '%' }" :class="{ hot: pct >= 80 }" /></div>
          <small class="muted">Hôm nay đã chi <b class="num">{{ fmt(spendToday) }}</b> / {{ fmt(f.dailySpendLimit) }} ({{ Math.round(pct) }}%)</small>
        </div>
      </div>
      <Switch v-model="f.killSwitchEnabled" label="Bật dừng khẩn" />
    </div>

    <Callout v-if="f.killSwitchEnabled && !s.mock && s.dryRun" tone="info">Đang ở chế độ Chạy thử: khi vượt mức, tool chỉ ghi Nhật ký chứ chưa tắt camp thật.</Callout>
    <Callout v-if="f.killSwitchEnabled" tone="warning">Sau khi dừng khẩn hôm nay, nếu bạn bật lại camp thì tool sẽ không tắt lại lần nữa trong ngày. Camp tắt sẽ không tự bật lại vào hôm sau, hãy dùng lịch “Bật camp buổi sáng”.</Callout>

    <Btn variant="primary" :icon="Save" :action="save">Lưu</Btn>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 8px; font-size: 14.5px; }
.row { display: flex; gap: 16px; align-items: flex-start; padding: 20px 0; border-bottom: 1px solid var(--border); }
.row.last { border-bottom: 0; }
.ic { width: 42px; height: 42px; border-radius: 14px; display: grid; place-items: center; flex: none; background: var(--accent-soft); color: var(--accent); }
.ic.danger { background: var(--danger-soft); color: var(--danger); }
.rb { flex: 1; min-width: 0; } h4 { font-size: 15.5px; margin-bottom: 4px; letter-spacing: -.01em; } .rb > p { font-size: 14px; margin-bottom: 12px; line-height: 1.6; }
.inl { display: flex; gap: 10px; align-items: center; } .inl .input { width: 110px; } .lim { max-width: 240px; }
.scope { margin: 2px 0 12px; } .scope .muted { margin: 8px 0 0; font-size: 13.5px; }
.ameters { display: grid; gap: 12px; margin: -2px 0 6px; } .am .ah { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 5px; font-size: 14px; }
.meter { margin: -4px 0 4px; } .bar { height: 7px; border-radius: 99px; background: var(--surface-3); overflow: hidden; margin-bottom: 6px; }
.bar i { display: block; height: 100%; background: var(--accent-grad); border-radius: 99px; transition: width .5s var(--ease); } .bar i.hot { background: linear-gradient(90deg, var(--warning), var(--danger)); }
@media (max-width: 640px) { .row { flex-wrap: wrap; } }
</style>
