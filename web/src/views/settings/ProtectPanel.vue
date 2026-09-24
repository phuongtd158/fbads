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

const s = state.settings
const f = reactive({
  skipLearning: s.skipLearning !== false,
  dailyChangeCapPct: s.dailyChangeCapPct || 30,
  killSwitchEnabled: !!s.killSwitchEnabled,
  dailySpendLimit: s.dailySpendLimit || '',
})
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
        <p class="muted">Khi <b>tổng chi tiêu hôm nay</b> của mọi camp đạt mức này, tool tự tắt tất cả camp đang chạy (mỗi ngày tối đa một lần) và báo qua Telegram. Kiểm tra theo chu kỳ rule ở Cài đặt → Chung.</p>
        <Field label="Mức chi tiêu tối đa mỗi ngày" :error="show('dailySpendLimit')">
          <input v-model="f.dailySpendLimit" class="input num lim" type="number" min="0" step="10000" placeholder="Ví dụ 3000000" @blur="touched.dailySpendLimit = true" />
        </Field>
        <div v-if="hasData && Number(f.dailySpendLimit) > 0" class="meter">
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
.meter { margin: -4px 0 4px; } .bar { height: 7px; border-radius: 99px; background: var(--surface-3); overflow: hidden; margin-bottom: 6px; }
.bar i { display: block; height: 100%; background: var(--accent-grad); border-radius: 99px; transition: width .5s var(--ease); } .bar i.hot { background: linear-gradient(90deg, var(--warning), var(--danger)); }
@media (max-width: 640px) { .row { flex-wrap: wrap; } }
</style>
