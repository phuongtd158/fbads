<script setup>
import { ref, computed, reactive, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { RefreshCw, Search, Power, SearchX, PlugZap, Megaphone, ArrowUp, ArrowDown, ArrowUpDown, Wallet } from 'lucide-vue-next'
import { state, loadObjs } from '../stores/app'
import { toast, toastError, confirm } from '../stores/ui'
import { api } from '../lib/api'
import { fmt, fmtDec, fmtCompact } from '../lib/format'
import { DELIVERY, deliveryMap } from '../lib/delivery'
import { groupByCurrency, countByAccount, accountLabel, decimalsOf } from '../lib/accounts'
import Btn from '../components/Btn.vue'
import Switch from '../components/Switch.vue'
import Badge from '../components/Badge.vue'
import Segmented from '../components/Segmented.vue'
import Skeleton from '../components/Skeleton.vue'
import EmptyState from '../components/EmptyState.vue'
import ProgressRing from '../components/ProgressRing.vue'
import AnimatedNumber from '../components/AnimatedNumber.vue'
import BudgetCell from '../components/BudgetCell.vue'
import InfoTip from '../components/InfoTip.vue'
import Callout from '../components/Callout.vue'
import BulkBudget from '../components/BulkBudget.vue'
import OnboardingCard from '../components/OnboardingCard.vue'
import { allDone, hidden as onboardHidden } from '../stores/onboarding'

const route = useRoute()
const q = ref(String(route.query.q || ''))
const filter = ref('all')
const level = ref('campaign')
// Nhiều tài khoản quảng cáo: lọc theo tài khoản ('' = tất cả); trình duyệt nhớ lựa chọn
const ACC_KEY = 'fbads.overviewAccount'
const account = ref((() => { try { return localStorage.getItem(ACC_KEY) || '' } catch { return '' } })())
watch(account, (v) => { try { localStorage.setItem(ACC_KEY, v) } catch { /* chế độ riêng tư */ } })
const busy = reactive({})
const bulkText = ref('')
const bulkBudget = ref(false) // hộp thoại đổi ngân sách hàng loạt
const searchEl = ref(null)
const showOnboarding = computed(() => !onboardHidden.value && !allDone.value)

watch(() => route.query.q, (v) => { if (v !== undefined) q.value = String(v) })
// nếu dữ liệu bị xoá khi đang xem (đổi chế độ, đổi tài khoản…) thì tự tải lại
watch(() => state.objsLoaded, (loaded) => { if (!loaded && !state.objsLoading) loadObjs() })
onMounted(() => {
  // đã có dữ liệu → làm mới ngầm (không nháy); chưa có → tải bình thường
  state.objsLoaded ? loadObjs(false, true) : loadObjs()
  window.addEventListener('keydown', slash)
})
const slash = (e) => { if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); searchEl.value && searchEl.value.focus() } }
onBeforeUnmount(() => window.removeEventListener('keydown', slash))

const staleWait = computed(() => { const t = state.objsMeta && state.objsMeta.blockedUntil; return t ? Math.max(1, Math.ceil((t - Date.now()) / 60000)) : 0 })
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
const accErrors = computed(() => (state.objsMeta && state.objsMeta.accountErrors) || [])
// tài khoản đã chọn không còn trong danh sách (đổi kết nối) → về "tất cả"
watch(accounts, (list) => { if (account.value && list.length && !list.some((a) => a.id === account.value)) account.value = '' })
const inAcc = (o) => !account.value || o.accountId === account.value
const showAccCol = computed(() => multiAcc.value && !account.value) // đang xem 1 tài khoản thì cột này chỉ lặp lại
const accCount = computed(() => countByAccount(state.objs.filter((o) => o.level === level.value)))
const errIds = computed(() => new Set(accErrors.value.map((x) => x.id)))
const accountOptions = computed(() => [
  { id: '', name: `Tất cả tài khoản (${accounts.value.length})` },
  ...accounts.value.map((a) => ({ id: a.id, name: `${a.name}${errIds.value.has(a.id) ? ' — không tải được' : ` (${accCount.value[a.id] || 0})`}` })),
])
const camps = computed(() => state.objs.filter((o) => o.level === 'campaign' && inAcc(o)))
// Khác loại tiền (vd VND + USD) thì không cộng chung được: tổng chi tiêu/ngân sách tính riêng từng loại tiền,
// CPA và ROAS chỉ hiện khi đang xem một loại tiền (chọn 1 tài khoản ở ô lọc)
// loại tiền khi chưa có camp nào để lấy (tài khoản đang chọn, không thì loại tiền đầu tiên của kết nối; conn.currency có thể là "VND, USD")
const fallbackCur = computed(() => {
  const a = accounts.value.find((x) => x.id === account.value)
  return (a && a.currency) || String((state.conn && state.conn.currency) || 'VND').split(',')[0].trim() || 'VND'
})
const curGroups = computed(() => groupByCurrency(camps.value, fallbackCur.value))
const mixed = computed(() => curGroups.value.length > 1)
const currency = computed(() => (curGroups.value[0] && curGroups.value[0].currency) || fallbackCur.value)
const money = (n, cur) => (decimalsOf(cur) ? fmtDec(n, 2) : fmt(n))
const hasAdsets = computed(() => state.objs.some((o) => o.level === 'adset'))
// Phân phối như Ads Manager (xét cả nhóm QC bên trong); "đang chạy" = thực sự đang phân phối
const deliv = computed(() => deliveryMap(state.objs))
const deliveryOf = (o) => DELIVERY[deliv.value[o.id]] || DELIVERY.off
const isRunning = (o) => !!deliveryOf(o).running
const running = computed(() => camps.value.filter(isRunning))
const totalSpend = computed(() => camps.value.reduce((t, o) => t + o.metrics.spend, 0))
const totalResults = computed(() => camps.value.reduce((t, o) => t + o.metrics.results, 0))
const activeBudget = computed(() => running.value.reduce((t, o) => t + (o.dailyBudget || 0), 0))
const budgetPct = computed(() => (activeBudget.value ? Math.min(100, (totalSpend.value / activeBudget.value) * 100) : 0))
// Tổng chi tiêu / ngân sách theo từng loại tiền (chỉ dùng khi xem nhiều loại tiền cùng lúc)
const byCur = computed(() => curGroups.value.map((g) => ({
  currency: g.currency,
  spend: g.items.reduce((t, o) => t + o.metrics.spend, 0),
  budget: g.items.filter(isRunning).reduce((t, o) => t + (o.dailyBudget || 0), 0),
})))
const avgCpa = computed(() => (!mixed.value && totalResults.value ? totalSpend.value / totalResults.value : null))
const avgRoas = computed(() => {
  if (mixed.value) return null
  const w = camps.value.filter((o) => o.metrics.roas != null && o.metrics.spend > 0)
  const s = w.reduce((t, o) => t + o.metrics.spend, 0)
  return s ? w.reduce((t, o) => t + o.metrics.roas * o.metrics.spend, 0) / s : null
})
const top = computed(() => (mixed.value ? [] : [...camps.value].filter((o) => o.metrics.spend > 0).sort((a, b) => b.metrics.spend - a.metrics.spend).slice(0, 3)))
const topMax = computed(() => (top.value[0] ? top.value[0].metrics.spend : 1))

const inLevel = computed(() => state.objs.filter((o) => o.level === level.value && inAcc(o)))
// ----- Sắp xếp theo cột (bấm tiêu đề cột: lần đầu theo chiều mặc định, bấm lại thì đảo chiều) -----
// Giá trị trống (CPA khi chưa có kết quả, ngân sách CBO…) luôn nằm cuối, dù tăng hay giảm.
const SORTS = {
  name: { label: 'Tên', get: (o) => o.name, text: true, first: 'asc' },
  account: { label: 'Tài khoản', get: (o) => accountLabel(o), text: true, first: 'asc' },
  delivery: { label: 'Phân phối', get: (o) => deliveryOf(o).rank, first: 'asc' },
  budget: { label: 'Ngân sách/ngày', get: (o) => o.dailyBudget },
  spend: { label: 'Chi tiêu', get: (o) => o.metrics.spend },
  results: { label: 'Kết quả', get: (o) => o.metrics.results },
  cpa: { label: 'CPA', get: (o) => o.metrics.cpa, first: 'asc' },
  roas: { label: 'ROAS', get: (o) => o.metrics.roas },
}
const SORT_KEY = 'fbads.overviewSort'
const readSort = () => { try { const v = JSON.parse(localStorage.getItem(SORT_KEY)); return v && SORTS[v.key] && ['asc', 'desc'].includes(v.dir) ? v : null } catch { return null } }
const sort = reactive(readSort() || { key: '', dir: 'desc' }) // key '' = thứ tự như trên Facebook
watch(sort, (v) => { try { localStorage.setItem(SORT_KEY, JSON.stringify(v)) } catch { /* chế độ riêng tư */ } })
function sortBy(k) {
  if (sort.key === k) sort.dir = sort.dir === 'asc' ? 'desc' : 'asc'
  else { sort.key = k; sort.dir = SORTS[k].first || 'desc' }
}
const sortIcon = (k) => (sort.key !== k ? ArrowUpDown : sort.dir === 'asc' ? ArrowUp : ArrowDown)
const sortTitle = (k) => `Sắp xếp theo ${SORTS[k].label}${sort.key === k ? (sort.dir === 'asc' ? ' (đang tăng dần, bấm để giảm dần)' : ' (đang giảm dần, bấm để tăng dần)') : ''}`
const collator = new Intl.Collator('vi', { numeric: true, sensitivity: 'base' })
function compare(a, b) {
  const s = SORTS[sort.key], va = s.get(a), vb = s.get(b)
  if (va == null || vb == null) return va == null && vb == null ? 0 : va == null ? 1 : -1
  const r = s.text ? collator.compare(va, vb) : va - vb
  return sort.dir === 'asc' ? r : -r
}
// Chọn cách sắp xếp trên điện thoại (không có hàng tiêu đề cột)
const mobileSort = computed({
  get: () => (sort.key ? `${sort.key}:${sort.dir}` : ''),
  set: (v) => { const [k, d] = v.split(':'); sort.key = k || ''; sort.dir = d || 'desc' },
})
const mobileSortOptions = computed(() => [
  ['', 'Mặc định (như Facebook)'], ['spend:desc', 'Chi tiêu: cao → thấp'], ['spend:asc', 'Chi tiêu: thấp → cao'],
  ['results:desc', 'Kết quả: nhiều → ít'], ['cpa:asc', 'CPA: thấp → cao'], ['cpa:desc', 'CPA: cao → thấp'],
  ['roas:desc', 'ROAS: cao → thấp'], ['budget:desc', 'Ngân sách: cao → thấp'], ['delivery:asc', 'Phân phối: đang chạy trước'],
  ['name:asc', 'Tên: A → Z'], ['name:desc', 'Tên: Z → A'],
  ...(multiAcc.value && !account.value ? [['account:asc', 'Tài khoản: A → Z'], ['account:desc', 'Tài khoản: Z → A']] : []),
])

const visible = computed(() => {
  const s = q.value.trim().toLowerCase()
  const list = inLevel.value.filter((o) => (filter.value === 'all' || (filter.value === 'on') === isRunning(o)) && (!s || o.name.toLowerCase().includes(s)))
  return sort.key ? list.sort(compare) : list
})
const filterOptions = computed(() => [
  { value: 'all', label: 'Tất cả', count: inLevel.value.length },
  { value: 'on', label: 'Đang chạy', count: inLevel.value.filter(isRunning).length },
  { value: 'off', label: 'Không chạy', count: inLevel.value.filter((o) => !isRunning(o)).length },
])
const levelOptions = [{ value: 'campaign', label: 'Chiến dịch' }, { value: 'adset', label: 'Nhóm QC' }]
const footResults = computed(() => visible.value.reduce((t, o) => t + o.metrics.results, 0))
// Chi tiêu ở chân bảng: nhiều loại tiền thì tách riêng từng loại
const footSpend = computed(() => groupByCurrency(visible.value, fallbackCur.value).map((g) => ({ currency: g.currency, s: g.items.reduce((t, o) => t + o.metrics.spend, 0) })))

const roasTone = (o) => (!o.metrics.spend || o.metrics.roas == null ? null : o.metrics.roas >= 2 ? 'success' : o.metrics.roas < 1 ? 'danger' : 'warning')
const settled = (o) => ['ACTIVE', 'PAUSED'].includes(o.effective)
// Facebook không cho bật camp đã lưu trữ/bị từ chối → khoá công tắc và giải thích
const locked = (o) => ['ARCHIVED', 'DELETED', 'DISAPPROVED'].includes(o.effective)

async function toggle(o, on) {
  const prev = { status: o.status, effective: o.effective }
  o.status = on ? 'ACTIVE' : 'PAUSED'; if (settled(o)) o.effective = o.status // cập nhật ngay (optimistic)
  busy[o.id] = true
  try {
    await api(`objects/${o.id}/status`, 'POST', { on, name: o.name })
    toast((on ? 'Đã bật: ' : 'Đã tắt: ') + o.name)
  } catch (e) { Object.assign(o, prev); toastError(e) } finally { busy[o.id] = false }
}

async function bulk(on) {
  const list = visible.value.filter((o) => (o.status === 'ACTIVE') !== on)
  if (!list.length) return toast(on ? 'Tất cả đã bật' : 'Tất cả đã tắt')
  const names = list.slice(0, 4).map((o) => o.name).join(', ') + (list.length > 4 ? ` và ${list.length - 4} mục khác` : '')
  if (!await confirm(`${on ? 'Bật' : 'Tắt'} ${list.length} mục?`, names, { ok: on ? 'Bật' : 'Tắt', danger: !on })) return
  let n = 0
  for (const o of list) {
    bulkText.value = `${on ? 'Đang bật' : 'Đang tắt'} ${++n}/${list.length}…`
    try { await api(`objects/${o.id}/status`, 'POST', { on, name: o.name }); o.status = on ? 'ACTIVE' : 'PAUSED'; if (settled(o)) o.effective = o.status } catch (e) { toastError(e) }
  }
  bulkText.value = ''
  toast(`Đã ${on ? 'bật' : 'tắt'} ${list.length} mục`)
}
</script>

<template>
  <div>
    <Teleport to="#page-actions" defer>
      <Btn :icon="RefreshCw" :loading="state.objsLoading" :action="() => loadObjs(true)">Làm mới</Btn>
    </Teleport>

    <OnboardingCard v-if="showOnboarding" />

    <!-- Bento KPI -->
    <div class="bento stagger">
      <section class="card hero">
        <template v-if="!state.objsLoaded"><Skeleton w="120px" /><Skeleton w="220px" h="38px" /><Skeleton w="80%" /></template>
        <template v-else>
          <div class="hero-l">
            <p class="lbl">Chi tiêu hôm nay</p>
            <template v-if="!mixed">
              <p class="big"><AnimatedNumber :value="totalSpend" :decimals="decimalsOf(currency)" /><small>{{ currency }}</small></p>
              <p class="muted sub">{{ activeBudget ? `trên tổng ngân sách ${money(activeBudget, currency)}` : 'Chưa có ngân sách cấp camp' }}</p>
            </template>
            <template v-else>
              <p v-for="g in byCur" :key="g.currency" class="big cur"><AnimatedNumber :value="g.spend" :decimals="decimalsOf(g.currency)" /><small>{{ g.currency }}</small><em v-if="g.budget" class="faint">trên ngân sách {{ money(g.budget, g.currency) }}</em></p>
              <p class="muted sub">Các tài khoản dùng {{ byCur.length }} loại tiền khác nhau nên không cộng chung. Chọn 1 tài khoản ở ô lọc để xem CPA và ROAS.</p>
            </template>
            <div v-if="top.length" class="tops">
              <div v-for="t in top" :key="t.id" class="top"><span class="tn" :title="t.name">{{ t.name }}</span><i class="tbar"><b :style="{ width: (t.metrics.spend / topMax) * 100 + '%' }" /></i><em class="num">{{ fmtCompact(t.metrics.spend) }}</em></div>
            </div>
          </div>
          <ProgressRing v-if="!mixed" :value="budgetPct" :size="128" :stroke="12"><div class="rc"><b class="num">{{ Math.round(budgetPct) }}%</b><small class="faint">ngân sách</small></div></ProgressRing>
        </template>
      </section>

      <section class="card kpi"><p class="lbl">Đang chạy</p>
        <Skeleton v-if="!state.objsLoaded" h="30px" w="90px" />
        <template v-else><p class="val"><AnimatedNumber :value="running.length" /><small> / {{ camps.length }}</small></p><p class="sub faint">{{ camps.length - running.length ? `${camps.length - running.length} camp đang dừng` : 'Tất cả đang chạy' }}</p></template>
      </section>
      <section class="card kpi"><p class="lbl">Kết quả <InfoTip tip="results" /></p>
        <Skeleton v-if="!state.objsLoaded" h="30px" w="90px" />
        <template v-else><p class="val"><AnimatedNumber :value="totalResults" /></p><p class="sub faint">theo “{{ state.settings.resultAction || 'purchase' }}”</p></template>
      </section>
      <section class="card kpi"><p class="lbl">CPA trung bình <InfoTip tip="cpa" /></p>
        <Skeleton v-if="!state.objsLoaded" h="30px" w="110px" />
        <template v-else><p class="val"><template v-if="avgCpa != null"><AnimatedNumber :value="avgCpa" /></template><template v-else>–</template></p><p class="sub faint">{{ mixed ? 'Khác loại tiền, chọn 1 tài khoản' : avgCpa != null ? 'Chi tiêu chia số kết quả' : 'Chưa có kết quả' }}</p></template>
      </section>
      <section class="card kpi"><p class="lbl">ROAS trung bình <InfoTip tip="roas" /></p>
        <Skeleton v-if="!state.objsLoaded" h="30px" w="80px" />
        <template v-else><p class="val" :class="avgRoas != null && (avgRoas >= 2 ? 'ok' : avgRoas < 1 ? 'bad' : 'warn')">{{ avgRoas != null ? fmtDec(avgRoas) : '–' }}</p><p class="sub faint">{{ mixed ? 'Khác loại tiền, chọn 1 tài khoản' : 'Doanh thu chia chi tiêu' }}</p></template>
      </section>
    </div>

    <!-- Bảng chiến dịch -->
    <section class="card panel">
      <div class="tb">
        <div class="search"><Search :size="16" /><input ref="searchEl" v-model="q" class="input" placeholder="Tìm chiến dịch…" /><kbd>/</kbd></div>
        <Segmented v-model="filter" :options="filterOptions" size="sm" />
        <select v-if="multiAcc" v-model="account" class="input accsel" aria-label="Tài khoản quảng cáo"><option v-for="a in accountOptions" :key="a.id" :value="a.id">{{ a.name }}</option></select>
        <Segmented v-if="hasAdsets" v-model="level" :options="levelOptions" size="sm" />
        <select v-model="mobileSort" class="input msort" aria-label="Sắp xếp">
          <option v-for="[v, l] in mobileSortOptions" :key="v" :value="v">{{ l }}</option>
        </select>
        <span class="sp" />
        <Btn size="sm" :icon="Wallet" :disabled="!state.objsLoaded" @click="bulkBudget = true">Đổi ngân sách hàng loạt</Btn>
        <Btn size="sm" :icon="Power" :action="() => bulk(true)">{{ bulkText.startsWith('Đang bật') ? bulkText : 'Bật tất cả' }}</Btn>
        <Btn size="sm" variant="danger" :icon="Power" :action="() => bulk(false)">{{ bulkText.startsWith('Đang tắt') ? bulkText : 'Tắt tất cả' }}</Btn>
      </div>

      <Callout v-if="accErrors.length" tone="danger" class="stale">
        <b>Không tải được {{ accErrors.length }} tài khoản:</b>
        <template v-for="(x, i) in accErrors" :key="x.id">{{ i ? '; ' : ' ' }}{{ x.name }} ({{ x.error }})</template>.
        Các tài khoản khác vẫn hiện bình thường. Kiểm tra quyền của token với tài khoản này ở Cài đặt → Kết nối Facebook.
      </Callout>
      <Callout v-if="state.objsMeta && state.objsMeta.stale && state.objsAt" class="stale">
        <b>Facebook đang giới hạn số lần gọi</b>, nên đây là số liệu lúc {{ state.objsAt.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }}, chưa phải số mới nhất.
        Tool tự tải lại{{ staleWait ? ` sau khoảng ${staleWait} phút` : ' khi được phép' }}, không cần bấm Làm mới. Lịch và rule vẫn chạy theo giờ; nếu Facebook từ chối thao tác, lỗi sẽ ghi ở Nhật ký.
      </Callout>
      <div v-if="!state.objsLoaded" class="skel"><div v-for="i in 5" :key="i"><Skeleton h="44px" r="12px" /></div></div>
      <EmptyState v-else-if="state.objsErr && !state.objs.length" :icon="PlugZap" tone="danger" title="Chưa tải được dữ liệu" :text="state.objsErr">
        <RouterLink to="/settings/connection"><Btn variant="primary">Kiểm tra kết nối</Btn></RouterLink>
      </EmptyState>
      <EmptyState v-else-if="!visible.length" :icon="q ? SearchX : Megaphone" :title="q ? 'Không có kết quả phù hợp' : 'Chưa có chiến dịch nào'" :text="q ? 'Thử đổi từ khoá hoặc bộ lọc.' : 'Khi tài khoản có chiến dịch, chúng sẽ hiện ở đây.'" />

      <div v-else class="table" :class="{ hasacc: showAccCol }">
        <div class="hd row">
          <span />
          <span><button type="button" class="sh" :class="{ on: sort.key === 'name' }" :title="sortTitle('name')" @click="sortBy('name')">{{ level === 'campaign' ? 'Chiến dịch' : 'Nhóm quảng cáo' }}<component :is="sortIcon('name')" :size="13" /></button></span>
          <span v-if="showAccCol" class="h-ac"><button type="button" class="sh" :class="{ on: sort.key === 'account' }" :title="sortTitle('account')" @click="sortBy('account')">Tài khoản<component :is="sortIcon('account')" :size="13" /></button></span>
          <span><button type="button" class="sh" :class="{ on: sort.key === 'delivery' }" :title="sortTitle('delivery')" @click="sortBy('delivery')">Phân phối<component :is="sortIcon('delivery')" :size="13" /></button></span>
          <div class="metrics">
            <span v-for="k in ['budget', 'spend', 'results', 'cpa', 'roas']" :key="k" class="r">
              <button type="button" class="sh" :class="{ on: sort.key === k }" :title="sortTitle(k)" @click="sortBy(k)">{{ SORTS[k].label }}<component :is="sortIcon(k)" :size="13" /></button>
              <InfoTip v-if="k === 'budget'" tip="budget" />
            </span>
          </div>
        </div>
        <TransitionGroup name="row" tag="div">
          <div v-for="o in visible" :key="o.id" class="row item" :class="{ off: o.status !== 'ACTIVE' }">
            <div class="c-sw"><Switch :model-value="o.status === 'ACTIVE'" :disabled="locked(o)" :title="locked(o) ? 'Camp đã lưu trữ hoặc bị từ chối, không thể bật' : ''" :loading="busy[o.id]" :label="'Bật/tắt ' + o.name" @update:model-value="(v) => toggle(o, v)" /></div>
            <div class="c-nm"><b :title="o.name">{{ o.name }}</b><small v-if="showAccCol" class="acc faint" :title="'Tài khoản quảng cáo ID ' + o.accountId">{{ accountLabel(o) }}</small><span v-if="o.learning && o.level === 'campaign'" class="bdg"><Badge tone="info" title="Có nhóm quảng cáo đang trong giai đoạn học: rule sẽ không đổi ngân sách camp này">Đang học</Badge></span></div>
            <div v-if="showAccCol" class="c-ac" :title="'Tài khoản quảng cáo ID ' + o.accountId"><b>{{ accountLabel(o) }}</b><small v-if="o.currency" class="faint">{{ o.currency }}</small></div>
            <div class="c-dl"><span class="dl" :class="deliveryOf(o).tone" :title="deliveryOf(o).label"><i />{{ deliveryOf(o).label }}</span></div>
            <div class="metrics">
              <div class="m r"><span class="ml">Ngân sách/ngày</span><BudgetCell :o="o" /></div>
              <div class="m r"><span class="ml">Chi tiêu</span><span class="num sp">{{ fmt(o.metrics.spend) }}</span>
                <i v-if="o.dailyBudget" class="mini"><b :style="{ width: Math.min(100, (o.metrics.spend / o.dailyBudget) * 100) + '%' }" /></i></div>
              <div class="m r"><span class="ml">Kết quả</span><span class="num">{{ o.metrics.results }}</span></div>
              <div class="m r"><span class="ml">CPA</span><span class="num">{{ fmt(o.metrics.cpa) }}</span></div>
              <div class="m r"><span class="ml">ROAS</span><Badge v-if="roasTone(o)" :tone="roasTone(o)" class="num">{{ fmtDec(o.metrics.roas) }}</Badge><span v-else class="faint">–</span></div>
            </div>
          </div>
        </TransitionGroup>
      </div>

      <div v-if="state.objsLoaded && visible.length" class="foot faint">
        <span><b class="num">{{ visible.length }}</b> mục</span>
        <span>Chi tiêu <template v-for="(g, i) in footSpend" :key="g.currency"><template v-if="i"> + </template><b class="num">{{ money(g.s, g.currency) }}</b><template v-if="footSpend.length > 1"> {{ g.currency }}</template></template></span>
        <span>Kết quả <b class="num">{{ fmt(footResults) }}</b></span>
        <span v-if="state.objsMeta && state.objsMeta.usage" class="right" :title="`Mức dùng lượt gọi Facebook API (${state.objsMeta.usage.tier || 'không rõ hạng'}). Tới 100% thì Facebook tạm chặn; tool tự giãn thời gian làm mới khi vượt 60%.`">
          API Facebook <b class="num" :class="{ warnc: state.objsMeta.usage.pct >= 60 }">{{ state.objsMeta.usage.pct }}%</b></span>
        <span v-if="state.objsAt" :class="{ right: !(state.objsMeta && state.objsMeta.usage) }">Số liệu lúc {{ state.objsAt.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }}</span>
      </div>
    </section>
    <BulkBudget v-model="bulkBudget" :level="level" :account="account" />
  </div>
</template>

<style scoped>
.bento { display: grid; grid-template-columns: repeat(12, 1fr); gap: 16px; margin-bottom: 20px; }
.hero { grid-column: span 6; grid-row: span 2; padding: 24px 26px; display: flex; align-items: center; justify-content: space-between; gap: 20px; overflow: hidden; position: relative; }
.hero::after { content: ''; position: absolute; inset: auto -60px -80px auto; width: 240px; height: 240px; border-radius: 50%; background: var(--accent-grad); opacity: .07; filter: blur(30px); pointer-events: none; }
.hero-l { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.lbl { font-size: 13.5px; font-weight: 600; color: var(--text-2); }
.big { font-size: 42px; font-weight: 750; letter-spacing: -.04em; line-height: 1.1; }
.big small { font-size: 15px; font-weight: 600; color: var(--text-3); margin-left: 8px; letter-spacing: 0; }
.sub { font-size: 13.5px; }
.rc { display: flex; flex-direction: column; line-height: 1.1; } .rc b { font-size: 26px; letter-spacing: -.03em; } .rc small { font-size: 12px; margin-top: 3px; }
.tops { margin-top: 16px; display: grid; gap: 9px; }
.top { display: grid; grid-template-columns: minmax(0, 1.3fr) 1fr auto; gap: 12px; align-items: center; font-size: 13.5px; }
.tn { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-2); }
.tbar { height: 6px; background: var(--surface-3); border-radius: 99px; overflow: hidden; }
.tbar b { display: block; height: 100%; background: var(--accent-grad); border-radius: 99px; transition: width .8s var(--ease); }
.top em { font-style: normal; font-weight: 650; min-width: 44px; text-align: right; }
.kpi { grid-column: span 3; padding: 18px 20px; display: flex; flex-direction: column; gap: 8px; transition: transform .2s var(--ease), box-shadow .2s; }
.kpi:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.val { font-size: 30px; font-weight: 720; letter-spacing: -.03em; line-height: 1.1; }
.val small { font-size: 15px; color: var(--text-3); font-weight: 600; letter-spacing: 0; }
.val.ok { color: var(--success); } .val.bad { color: var(--danger); } .val.warn { color: var(--warning); }
.kpi .sub { font-size: 13px; }

.panel { overflow: hidden; }
.tb { display: flex; align-items: center; gap: 12px; padding: 16px 18px; flex-wrap: wrap; border-bottom: 1px solid var(--border); }
.tb .sp { flex: 1; }
.search { position: relative; flex: 1 1 220px; max-width: 320px; }
.search svg { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.search .input { padding: 8px 40px 8px 36px; }
.search kbd { position: absolute; right: 9px; top: 50%; transform: translateY(-50%); }
.skel { padding: 16px 18px; display: grid; gap: 12px; }

/* bảng dạng lưới: máy tính = bảng; điện thoại = thẻ */
.row { display: grid; grid-template-columns: 70px minmax(200px, 2fr) minmax(150px, 1fr) minmax(0, 3.6fr); align-items: center; padding: 0 18px; gap: 6px; }
/* tiêu đề cột bấm được để sắp xếp */
.sh { display: inline-flex; align-items: center; gap: 5px; border: 0; background: none; padding: 4px 6px; margin: -4px -6px; border-radius: 7px; font: inherit; color: inherit; cursor: pointer; white-space: nowrap; }
.sh svg { opacity: .45; flex: none; }
.sh:hover { color: var(--text); background: var(--surface-3); } .sh:hover svg { opacity: .8; }
.sh.on { color: var(--accent); } .sh.on svg { opacity: 1; }
.hd .r { display: inline-flex; justify-content: flex-end; align-items: center; gap: 2px; }
.msort { display: none; width: auto; padding: 7px 10px; font-size: 13.5px; }
/* cột Phân phối: chấm màu + chữ như Ads Manager */
.c-dl { min-width: 0; }
.dl { display: inline-flex; align-items: center; gap: 7px; font-size: 13.5px; color: var(--text-2); max-width: 100%; }
.dl i { width: 9px; height: 9px; border-radius: 50%; background: var(--text-3); opacity: .6; flex: none; }
.dl.success { color: var(--text); } .dl.success i { background: var(--success); opacity: 1; }
.dl.info i { background: var(--info); opacity: 1; } .dl.warning i { background: var(--warning); opacity: 1; } .dl.danger { color: var(--danger); } .dl.danger i { background: var(--danger); opacity: 1; }
.metrics { display: grid; grid-template-columns: 1.15fr 1.15fr .7fr .9fr .8fr; gap: 10px; align-items: center; }
.r { text-align: right; justify-self: end; }
.hd { padding-top: 11px; padding-bottom: 11px; font-size: 12.5px; font-weight: 650; color: var(--text-3); background: var(--surface-2); border-bottom: 1px solid var(--border); letter-spacing: .01em; }
.hd .metrics > span { width: 100%; text-align: right; }
.item { min-height: 68px; border-bottom: 1px solid var(--border); transition: background .15s; }
.item:last-child { border-bottom: 0; }
.item:hover { background: var(--surface-2); }
.bdg { display: flex; gap: 6px; flex-wrap: wrap; }
.item.off .c-nm b { color: var(--text-2); }
.c-nm { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 3px; padding: 12px 0; }
.c-nm b { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 15px; font-weight: 620; }
.m { display: flex; flex-direction: column; align-items: flex-end; gap: 3px; min-width: 0; }
.ml { display: none; font-size: 12px; color: var(--text-3); }
.sp { font-weight: 600; }
.mini { width: 74px; height: 4px; border-radius: 9px; background: var(--surface-3); overflow: hidden; }
.mini b { display: block; height: 100%; background: var(--accent-grad); }
.foot { display: flex; gap: 22px; flex-wrap: wrap; padding: 13px 20px; border-top: 1px solid var(--border); font-size: 13.5px; }
.foot b { color: var(--text); } .foot .right { margin-left: auto; } .foot b.warnc { color: var(--warning); }
.stale { margin: 14px 18px 0; }
.accsel { width: auto; max-width: 240px; padding: 7px 10px; font-size: 13.5px; }
/* cột Tài khoản (chỉ máy tính; điện thoại không có hàng tiêu đề nên hiện tên tài khoản dưới tên chiến dịch) */
.table.hasacc .row { grid-template-columns: 70px minmax(180px, 1.9fr) minmax(130px, 1.1fr) minmax(140px, .9fr) minmax(0, 3.4fr); }
.c-nm .acc { display: none; font-size: 12.5px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: -2px; }
.c-ac { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; padding: 12px 0; }
.c-ac b { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13.5px; font-weight: 600; color: var(--text-2); }
.c-ac small { font-size: 11.5px; padding: 1px 7px; border-radius: 6px; background: var(--surface-3); }
.big.cur { font-size: 30px; display: flex; align-items: baseline; flex-wrap: wrap; }
.big.cur em { font-style: normal; font-size: 13px; font-weight: 500; letter-spacing: 0; margin-left: 12px; }
.row-enter-active, .row-leave-active { transition: all .3s var(--ease); }
.row-enter-from, .row-leave-to { opacity: 0; transform: translateX(-10px); }

@media (max-width: 1100px) { .hero { grid-column: span 12; grid-row: auto; } .kpi { grid-column: span 6; } }
@media (max-width: 860px) {
  .hd { display: none; }
  .row, .table.hasacc .row { grid-template-columns: auto minmax(0, 1fr); padding: 14px 16px; gap: 10px 14px; }
  .c-nm .acc { display: block; }
  .c-ac { display: none; }
  .metrics { grid-column: 1 / -1; grid-template-columns: repeat(2, 1fr); gap: 12px; padding-top: 12px; border-top: 1px dashed var(--border); }
  .m { align-items: flex-start; } .r { justify-self: start; text-align: left; }
  .ml { display: block; }
  .c-nm { padding: 0; }
  .c-dl { grid-column: 2; margin-top: -6px; }
  .msort { display: block; }
  .item { min-height: 0; }
  .big { font-size: 34px; }
  .hero { flex-direction: column; align-items: flex-start; }
  .kpi { grid-column: span 6; }
  .tb .sp { display: none; }
}
</style>
