<script setup>
// Mục tiêu theo từng tài khoản: CPA tối đa chấp nhận được, ROAS tối thiểu, và mức chi tiêu dừng khẩn riêng.
// Rule có thể so với "% mục tiêu" nên một rule dùng chung được cho nhiều tài khoản có ngưỡng khác nhau.
import { reactive, ref, computed, watch, onMounted } from 'vue'
import { Save, Target } from 'lucide-vue-next'
import { state, saveSettings, ensureObjs } from '../../stores/app'
import { toast } from '../../stores/ui'
import { validateSettings } from '../../lib/validate'
import { totals } from '../../lib/metrics'
import { fmt, fmtDec } from '../../lib/format'
import Btn from '../../components/Btn.vue'
import Callout from '../../components/Callout.vue'

const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const rows = reactive({}) // { [mã tài khoản]: { cpa, roas, dailySpendLimit } dạng chuỗi để nhập
const error = ref('')
const saved = computed(() => state.settings.accountTargets || {})
onMounted(() => ensureObjs())

// Dựng dòng nhập cho mỗi tài khoản đang quản lý (giữ nguyên chữ đang gõ dở khi danh sách làm mới)
watch(accounts, (list) => {
  for (const a of list) {
    if (!rows[a.id]) { const t = saved.value[a.id] || {}; rows[a.id] = { cpa: t.cpa || '', roas: t.roas || '', dailySpendLimit: t.dailySpendLimit || '' } }
  }
}, { immediate: true })

// Số hiện tại của mỗi tài khoản (hôm nay) để tham khảo khi đặt mục tiêu
const today = (id) => {
  const list = state.objs.filter((o) => o.level === 'campaign' && o.accountId === id)
  return list.length ? totals(list.map((o) => ({ m: o.metrics, budget: null }))) : null
}
const payload = computed(() => Object.fromEntries(accounts.value.map((a) => [a.id, rows[a.id] || {}])))
const check = computed(() => validateSettings({ accountTargets: payload.value }, state.settings))
const hasAny = computed(() => Object.values(saved.value).some((t) => t && (t.cpa || t.roas || t.dailySpendLimit)))

async function save() {
  error.value = ''
  if (!check.value.ok) { error.value = check.value.errors.accountTargets || 'Có giá trị không hợp lệ'; toast(error.value, 'error'); return }
  await saveSettings({ accountTargets: check.value.value.accountTargets })
  toast('Đã lưu mục tiêu theo tài khoản')
}
</script>

<template>
  <section class="card pad">
    <h3>Mục tiêu theo tài khoản</h3>
    <p class="muted sub">Đặt mức <b>hoà vốn</b> của từng tài khoản một lần, rồi viết rule theo “<b>% mục tiêu</b>” (ví dụ “tắt khi CPA vượt 120% mục tiêu”). Mỗi tài khoản được so với mục tiêu của chính nó, nên một rule dùng chung được cho nhiều tài khoản. Để trống = chưa đặt.</p>

    <Callout v-if="!accounts.length" tone="info">Chưa có tài khoản quảng cáo nào. Kết nối Facebook ở tab “Kết nối Facebook” trước.</Callout>

    <div v-for="a in accounts" :key="a.id" class="acc">
      <header>
        <span class="ic"><Target :size="18" /></span>
        <div class="nm"><b>{{ a.name }}</b><small class="faint">ID {{ a.id }}<template v-if="a.currency"> · {{ a.currency }}</template></small></div>
        <small v-if="today(a.id)" class="now faint">Hôm nay: CPA <b class="num">{{ today(a.id).cpa != null ? fmt(today(a.id).cpa) : '–' }}</b> · ROAS <b class="num">{{ today(a.id).roas != null ? fmtDec(today(a.id).roas) : '–' }}</b></small>
      </header>
      <div class="grid" v-if="rows[a.id]">
        <label><span>CPA mục tiêu (tối đa)</span><input v-model="rows[a.id].cpa" type="number" min="0" step="any" class="input" placeholder="Chưa đặt" :aria-label="'CPA mục tiêu của ' + a.name" /><small class="faint">Rule so “CPA &gt; x% mục tiêu”</small></label>
        <label><span>ROAS mục tiêu (tối thiểu)</span><input v-model="rows[a.id].roas" type="number" min="0" step="any" class="input" placeholder="Chưa đặt" :aria-label="'ROAS mục tiêu của ' + a.name" /><small class="faint">Rule so “ROAS &lt; x% mục tiêu”</small></label>
        <label><span>Mức dừng khẩn riêng / ngày</span><input v-model="rows[a.id].dailySpendLimit" type="number" min="0" step="any" class="input" placeholder="Dùng mức chung" :aria-label="'Mức dừng khẩn của ' + a.name" /><small class="faint">Chỉ áp dụng khi dừng khẩn ở chế độ “Từng tài khoản”</small></label>
      </div>
    </div>

    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <div v-if="accounts.length" class="btns"><Btn variant="primary" :icon="Save" :action="save">Lưu mục tiêu</Btn><span v-if="!hasAny" class="faint hint">Chưa đặt mục tiêu nào.</span></div>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 18px; font-size: 14.5px; line-height: 1.6; }
.acc { padding: 16px 18px; margin-bottom: 14px; border: 1px solid var(--border); border-radius: var(--r-md); background: var(--surface-2); }
header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.ic { width: 34px; height: 34px; border-radius: 11px; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); flex: none; }
.nm { display: flex; flex-direction: column; min-width: 0; flex: 1; } .nm b { font-size: 15.5px; } .nm small { font-size: 12.5px; }
.now { font-size: 13px; } .now b { color: var(--text-2); }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.grid label { display: block; min-width: 0; } .grid label > span { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; }
.grid small { display: block; margin-top: 5px; font-size: 12px; }
.err { color: var(--danger); font-size: 13.5px; margin: 4px 0 10px; }
.btns { display: flex; align-items: center; gap: 14px; margin-top: 6px; } .hint { font-size: 13.5px; }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
