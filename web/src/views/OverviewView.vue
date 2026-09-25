<script setup>
import { ref, computed, reactive, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { RefreshCw, Search, Power, SearchX, PlugZap, Megaphone, ArrowUp, ArrowDown, ArrowUpDown, Wallet, Zap, ChevronDown, ChevronsDown, X, FilterX } from 'lucide-vue-next'
import { state, loadObjs } from '../stores/app'
import { ov, rangeInfo, rangeReady, loadRange, setSpec, itemOf, clearFilters, todayISO } from '../stores/overview'
import { toast, toastError, confirm } from '../stores/ui'
import { api } from '../lib/api'
import { fmt, fmtDec, fmtCompact } from '../lib/format'
import { DELIVERY, deliveryMap } from '../lib/delivery'
import { groupByCurrency, countByAccount, accountLabel, decimalsOf } from '../lib/accounts'
import { totals, runningBudget } from '../lib/metrics'
import { isToday } from '../lib/dates'
import { colOf, cellValue, cellText, money } from '../lib/overviewColumns'
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
import DateRangePicker from '../components/DateRangePicker.vue'
import AccountFilter from '../components/AccountFilter.vue'
import ColumnsMenu from '../components/ColumnsMenu.vue'
import Popover from '../components/Popover.vue'
import { allDone, hidden as onboardHidden } from '../stores/onboarding'

const route = useRoute()
const q = ref(String(route.query.q || ''))
const busy = reactive({})
const bulkText = ref('')
const bulkBudget = ref(false) // hộp thoại đổi ngân sách hàng loạt
const actOpen = ref(false)
const searchEl = ref(null)
const showOnboarding = computed(() => !onboardHidden.value && !allDone.value)
const today = computed(() => rangeInfo.value.today) // đang xem số liệu hôm nay (khác: khoảng ngày khác)

watch(() => route.query.q, (v) => { if (v !== undefined) q.value = String(v) })
// nếu dữ liệu bị xoá khi đang xem (đổi chế độ, đổi tài khoản…) thì tự tải lại
watch(() => state.objsLoaded, (loaded) => { if (!loaded && !state.objsLoading) loadObjs() })
// mỗi lần danh sách camp được làm mới (tự động mỗi 60 giây hoặc bấm Làm mới) thì cập nhật cả số liệu khoảng ngày (server dùng lại bản mới tải nên nhẹ)
watch(() => state.objsAt, () => { if (!isToday(ov.spec)) loadRange({ bg: true }) })
onMounted(() => {
  // đã có dữ liệu → làm mới ngầm (không nháy); chưa có → tải bình thường
  state.objsLoaded ? loadObjs(false, true) : loadObjs()
  loadRange({ bg: rangeReady.value })
  window.addEventListener('keydown', slash)
})
const slash = (e) => { if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); searchEl.value && searchEl.value.focus() } }
onBeforeUnmount(() => window.removeEventListener('keydown', slash))
const refreshAll = () => Promise.all([loadObjs(true), loadRange({ force: true })])

const staleWait = computed(() => { const t = state.objsMeta && state.objsMeta.blockedUntil; return t ? Math.max(1, Math.ceil((t - Date.now()) / 60000)) : 0 })
const rangeStale = computed(() => !today.value && !!(ov.data && ov.data.stale))
const stalePanel = computed(() => (state.objsMeta && state.objsMeta.stale && state.objsAt) || rangeStale.value)

// ----- Tài khoản -----
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
const accErrors = computed(() => (state.objsMeta && state.objsMeta.accountErrors) || [])
const errIds = computed(() => new Set(accErrors.value.map((x) => x.id)))
// tài khoản đã chọn mà không còn trong danh sách (đổi kết nối) → bỏ khỏi bộ lọc
watch(accounts, (list) => {
  if (!list.length || !ov.accounts.length) return
  const ok = ov.accounts.filter((id) => list.some((a) => a.id === id))
  if (ok.length !== ov.accounts.length) ov.accounts = ok
})
const inAcc = (o) => !ov.accounts.length || ov.accounts.includes(o.accountId)
const singleAcc = computed(() => (ov.accounts.length === 1 ? ov.accounts[0] : ''))
const showAccCol = computed(() => multiAcc.value && ov.accounts.length !== 1) // đang xem đúng 1 tài khoản thì cột này chỉ lặp lại
const accCount = computed(() => countByAccount(state.objs.filter((o) => o.level === ov.level)))

// ----- Số liệu (theo khoảng ngày đang chọn) -----
// Khác loại tiền (vd VND + USD) thì không cộng chung được: tổng tính riêng từng loại tiền,
// CPA và ROAS chỉ hiện khi đang xem một loại tiền (chọn 1 tài khoản ở ô lọc)
// loại tiền khi chưa có camp nào để lấy (tài khoản đang chọn, không thì loại tiền đầu tiên của kết nối; conn.currency có thể là "VND, USD")
const fallbackCur = computed(() => {
  const a = accounts.value.find((x) => x.id === singleAcc.value)
  return (a && a.currency) || String((state.conn && state.conn.currency) || 'VND').split(',')[0].trim() || 'VND'
})
const cur = (o) => o.currency || fallbackCur.value
const hasAdsets = computed(() => state.objs.some((o) => o.level === 'adset'))
watch(hasAdsets, (v) => { if (!v && ov.level === 'adset') ov.level = 'campaign' })
// Phân phối như Ads Manager (xét cả nhóm QC bên trong); "đang chạy" = thực sự đang phân phối
const deliv = computed(() => deliveryMap(state.objs))
const deliveryOf = (o) => DELIVERY[deliv.value[o.id]] || DELIVERY.off
const isRunning = (o) => !!deliveryOf(o).running

const campItems = computed(() => state.objs.filter((o) => o.level === 'campaign' && inAcc(o)).map(itemOf))
const running = computed(() => campItems.value.filter((i) => isRunning(i.o)))
const curGroups = computed(() => groupByCurrency(campItems.value, fallbackCur.value))
const mixed = computed(() => curGroups.value.length > 1)
const currency = computed(() => (curGroups.value[0] && curGroups.value[0].currency) || fallbackCur.value)
const T = computed(() => totals(campItems.value.map((i) => ({ m: i.m, budget: null }))))
// Ngân sách có thể đặt ở chiến dịch (CBO) hoặc ở nhóm QC (ABO): tính cả hai, không chỉ ngân sách cấp chiến dịch
const adsetObjs = computed(() => state.objs.filter((o) => o.level === 'adset'))
const budgetOf = (items) => runningBudget(items.map((i) => i.o), adsetObjs.value, isRunning)
const activeBudget = computed(() => budgetOf(running.value))
const budgetPct = computed(() => (activeBudget.value ? Math.min(100, (T.value.spend / activeBudget.value) * 100) : 0))
// Tổng chi tiêu / ngân sách theo từng loại tiền (chỉ dùng khi xem nhiều loại tiền cùng lúc)
const byCur = computed(() => curGroups.value.map((g) => ({
  currency: g.currency,
  spend: g.items.reduce((t, i) => t + i.m.spend, 0),
  budget: budgetOf(g.items.filter((i) => isRunning(i.o))),
})))
const avgCpa = computed(() => (mixed.value ? null : T.value.cpa))
const avgRoas = computed(() => (mixed.value ? null : T.value.roas))
const avgPerDay = computed(() => (!today.value && !mixed.value && rangeInfo.value.days && T.value.spend ? T.value.spend / rangeInfo.value.days : null))
const top = computed(() => (mixed.value ? [] : [...campItems.value].filter((i) => i.m.spend > 0).sort((a, b) => b.m.spend - a.m.spend).slice(0, 3)))
const topMax = computed(() => (top.value[0] ? top.value[0].m.spend : 1))
const kpiLoading = computed(() => !state.objsLoaded || !rangeReady.value)

// ----- Danh sách -----
const cols = computed(() => ov.columns.map(colOf))
const inLevel = computed(() => state.objs.filter((o) => o.level === ov.level && inAcc(o)).map(itemOf))

// Sắp xếp theo cột (bấm tiêu đề cột: lần đầu theo chiều mặc định, bấm lại thì đảo chiều).
// Giá trị trống (CPA khi chưa có kết quả, ngân sách CBO…) luôn nằm cuối, dù tăng hay giảm.
const SORT_META = { name: { label: 'Tên', text: true, first: 'asc' }, account: { label: 'Tài khoản', text: true, first: 'asc' }, delivery: { label: 'Phân phối', first: 'asc' } }
const sortMeta = (k) => SORT_META[k] || { label: colOf(k).label, first: colOf(k).first || 'desc' }
// cột đang được sắp xếp mà đã bị ẩn đi (hoặc không còn) thì coi như không sắp xếp
const sortKey = computed(() => { const k = ov.sort.key; return k && (SORT_META[k] || ov.columns.includes(k)) && (k !== 'account' || showAccCol.value) ? k : '' })
const sortValue = (k, it) => (k === 'name' ? it.o.name : k === 'account' ? accountLabel(it.o) : k === 'delivery' ? deliveryOf(it.o).rank : cellValue(k, it))
function sortBy(k) {
  if (sortKey.value === k) ov.sort = { key: k, dir: ov.sort.dir === 'asc' ? 'desc' : 'asc' }
  else ov.sort = { key: k, dir: sortMeta(k).first }
}
const sortIcon = (k) => (sortKey.value !== k ? ArrowUpDown : ov.sort.dir === 'asc' ? ArrowUp : ArrowDown)
const sortTitle = (k) => `Sắp xếp theo ${sortMeta(k).label}${sortKey.value === k ? (ov.sort.dir === 'asc' ? ' (đang tăng dần, bấm để giảm dần)' : ' (đang giảm dần, bấm để tăng dần)') : ''}`
const collator = new Intl.Collator('vi', { numeric: true, sensitivity: 'base' })
function compare(a, b) {
  const k = sortKey.value, va = sortValue(k, a), vb = sortValue(k, b)
  if (va == null || vb == null) return va == null && vb == null ? 0 : va == null ? 1 : -1
  const r = SORT_META[k] && SORT_META[k].text ? collator.compare(va, vb) : va - vb
  return ov.sort.dir === 'asc' ? r : -r
}
// Chọn cách sắp xếp trên điện thoại (không có hàng tiêu đề cột)
const dirText = (k, d) => (SORT_META[k] && SORT_META[k].text ? (d === 'asc' ? 'A → Z' : 'Z → A') : k === 'delivery' ? (d === 'asc' ? 'đang chạy trước' : 'không chạy trước') : d === 'asc' ? 'thấp → cao' : 'cao → thấp')
const mobileSortOptions = computed(() => {
  const out = [['', 'Mặc định (như Facebook)']]
  for (const k of ['name', ...(showAccCol.value ? ['account'] : []), 'delivery', ...ov.columns]) {
    const first = sortMeta(k).first, other = first === 'asc' ? 'desc' : 'asc'
    out.push([`${k}:${first}`, `${sortMeta(k).label}: ${dirText(k, first)}`], [`${k}:${other}`, `${sortMeta(k).label}: ${dirText(k, other)}`])
  }
  return out
})
const mobileSort = computed({
  get: () => (sortKey.value ? `${sortKey.value}:${ov.sort.dir}` : ''),
  set: (v) => { const [k, d] = v.split(':'); ov.sort = { key: k || '', dir: d || 'desc' } },
})

const visible = computed(() => {
  const s = q.value.trim().toLowerCase()
  const list = inLevel.value.filter((i) => (ov.status === 'all' || (ov.status === 'on') === isRunning(i.o))
    && (!s || i.o.name.toLowerCase().includes(s) || (multiAcc.value && accountLabel(i.o).toLowerCase().includes(s))))
  return sortKey.value ? [...list].sort(compare) : list
})
// Chỉ VẼ một trang: vài nghìn chiến dịch mà vẽ hết thì trang nặng và giật (nhất là điện thoại). Lọc, sắp xếp, đếm, hàng Tổng
// và bật/tắt hàng loạt vẫn tính trên toàn bộ danh sách đã lọc (visible); nút "Xem thêm" vẽ thêm từng trang.
const pageSize = () => (window.matchMedia('(max-width: 860px)').matches ? 30 : 60)
const limit = ref(pageSize())
const shown = computed(() => (visible.value.length > limit.value ? visible.value.slice(0, limit.value) : visible.value))
const moreCount = computed(() => Math.min(pageSize(), visible.value.length - shown.value.length))
// đổi bộ lọc / sắp xếp / khoảng ngày → về trang đầu (làm mới số liệu tự động thì giữ nguyên số dòng đang xem)
watch([q, () => ov.status, () => ov.level, () => ov.accounts, () => ov.sort, () => ov.spec], () => { limit.value = pageSize() }, { deep: true })
const statusOptions = computed(() => [
  { value: 'all', label: 'Tất cả', count: inLevel.value.length },
  { value: 'on', label: 'Đang chạy', count: inLevel.value.filter((i) => isRunning(i.o)).length },
  { value: 'off', label: 'Không chạy', count: inLevel.value.filter((i) => !isRunning(i.o)).length },
])
const levelOptions = computed(() => [
  { value: 'campaign', label: 'Chiến dịch', count: state.objs.filter((o) => o.level === 'campaign' && inAcc(o)).length },
  { value: 'adset', label: 'Nhóm QC', count: state.objs.filter((o) => o.level === 'adset' && inAcc(o)).length },
])
const levelName = computed(() => (ov.level === 'campaign' ? 'chiến dịch' : 'nhóm QC'))

// Hàng tổng: cộng số liệu rồi tính lại CPA/ROAS/CTR… từ tổng. Nhiều loại tiền thì cột tiền để trống (không cộng chung được)
const visCurs = computed(() => groupByCurrency(visible.value, fallbackCur.value))
const visMixed = computed(() => visCurs.value.length > 1)
const tot = computed(() => totals(visible.value.map((i) => ({ m: i.m, budget: i.o.dailyBudget }))))
const totCell = (c) => {
  if (c.key === 'budget') return tot.value.budgetRows && !visMixed.value ? money(tot.value.budget, currency.value) : '–'
  if (c.money && visMixed.value) return '–'
  if (c.key === 'roas') return tot.value.roas == null || visMixed.value ? '–' : fmtDec(tot.value.roas)
  return cellText(c, tot.value[c.key], visCurs.value[0] ? visCurs.value[0].currency : fallbackCur.value)
}
const footSpend = computed(() => visCurs.value.map((g) => ({ currency: g.currency, s: g.items.reduce((t, i) => t + i.m.spend, 0) })))
const footResults = computed(() => visible.value.reduce((t, i) => t + i.m.results, 0))

const lead = computed(() => 3 + (showAccCol.value ? 1 : 0)) // số cột đầu (công tắc, tên, [tài khoản], phân phối) mà nhãn hàng tổng trải ngang qua
// Bảng có thể rộng hơn khung (nhiều cột): cuộn ngang, giữ cố định 2 cột đầu, tiêu đề và hàng tổng
const gridStyle = computed(() => {
  const acc = showAccCol.value
  const tracks = ['70px', 'minmax(170px, 2.2fr)', ...(acc ? ['minmax(120px, 1fr)'] : []), 'minmax(108px, .9fr)', ...cols.value.map((c) => `minmax(${c.min}px, 1fr)`)]
  const minw = 70 + 170 + (acc ? 120 : 0) + 108 + cols.value.reduce((t, c) => t + c.min, 0) + 18 + 6 * (tracks.length - 1)
  return { '--cols': tracks.join(' '), '--minw': minw + 'px' }
})

// ----- Bộ lọc đang áp dụng -----
const STATUS_LABEL = { on: 'Đang chạy', off: 'Không chạy' }
const chips = computed(() => {
  const out = []
  if (ov.accounts.length) {
    const names = ov.accounts.map((id) => (accounts.value.find((a) => a.id === id) || { name: id }).name)
    out.push({ k: 'Tài khoản', v: names.length > 2 ? `${names.slice(0, 2).join(', ')} +${names.length - 2}` : names.join(', '), clear: () => { ov.accounts = [] } })
  }
  if (ov.status !== 'all') out.push({ k: 'Trạng thái', v: STATUS_LABEL[ov.status], clear: () => { ov.status = 'all' } })
  if (q.value.trim()) out.push({ k: 'Từ khoá', v: q.value.trim(), clear: () => { q.value = '' } })
  return out
})
const clearAll = () => { clearFilters(); q.value = '' }

const roasTone = (m) => (!m.spend || m.roas == null ? null : m.roas >= 2 ? 'success' : m.roas < 1 ? 'danger' : 'warning')
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

// Bật/tắt hàng loạt: chỉ các mục đang hiển thị sau khi lọc
const toOn = computed(() => visible.value.filter((i) => i.o.status !== 'ACTIVE').length)
const toOff = computed(() => visible.value.filter((i) => i.o.status === 'ACTIVE').length)
async function bulk(on) {
  const list = visible.value.map((i) => i.o).filter((o) => (o.status === 'ACTIVE') !== on)
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
      <Btn :icon="RefreshCw" :loading="state.objsLoading || ov.loading" :action="refreshAll">Làm mới</Btn>
    </Teleport>

    <!-- Thanh lọc chung: khoảng ngày và tài khoản áp dụng cho cả thẻ chỉ số lẫn bảng; dính ở đầu trang khi cuộn -->
    <div class="gbar">
      <DateRangePicker :model-value="ov.spec" :today="todayISO()" :loading="ov.loading" @update:model-value="setSpec" />
      <AccountFilter v-if="multiAcc" v-model="ov.accounts" :accounts="accounts" :counts="accCount" :err-ids="errIds" />
      <span v-if="!today" class="gnote faint">Số liệu <b>{{ rangeInfo.title.toLowerCase() }}</b>: chi tiêu, kết quả, CPA, ROAS… Ngân sách và trạng thái luôn là hiện tại.</span>
    </div>

    <OnboardingCard v-if="showOnboarding" />

    <!-- Bento KPI (theo khoảng ngày và tài khoản đang chọn) -->
    <div class="bento stagger">
      <section class="card hero">
        <template v-if="kpiLoading"><Skeleton w="120px" /><Skeleton w="220px" h="38px" /><Skeleton w="80%" /></template>
        <template v-else>
          <div class="hero-l">
            <p class="lbl">Chi tiêu {{ today ? 'hôm nay' : '· ' + rangeInfo.title.toLowerCase() }}</p>
            <template v-if="!mixed">
              <p class="big"><AnimatedNumber :value="T.spend" :decimals="decimalsOf(currency)" /><small>{{ currency }}</small></p>
              <p v-if="today" class="muted sub">{{ activeBudget ? `trên tổng ngân sách ${money(activeBudget, currency)}` : 'Chưa có ngân sách hằng ngày (chỉ có ngân sách trọn đời?)' }}</p>
              <p v-else class="muted sub">{{ rangeInfo.dates }}<template v-if="rangeInfo.days"> · {{ rangeInfo.days }} ngày</template><template v-if="avgPerDay != null"> · TB {{ money(avgPerDay, currency) }}/ngày</template></p>
            </template>
            <template v-else>
              <p v-for="g in byCur" :key="g.currency" class="big cur"><AnimatedNumber :value="g.spend" :decimals="decimalsOf(g.currency)" /><small>{{ g.currency }}</small><em v-if="today && g.budget" class="faint">trên ngân sách {{ money(g.budget, g.currency) }}</em></p>
              <p class="muted sub">Các tài khoản dùng {{ byCur.length }} loại tiền khác nhau nên không cộng chung. Chọn 1 tài khoản ở ô lọc để xem CPA và ROAS.</p>
            </template>
            <div v-if="top.length" class="tops">
              <div v-for="t in top" :key="t.o.id" class="top"><span class="tn" :title="t.o.name">{{ t.o.name }}</span><i class="tbar"><b :style="{ width: (t.m.spend / topMax) * 100 + '%' }" /></i><em class="num">{{ fmtCompact(t.m.spend) }}</em></div>
            </div>
          </div>
          <ProgressRing v-if="!mixed && today" :value="budgetPct" :size="128" :stroke="12"><div class="rc"><b class="num">{{ Math.round(budgetPct) }}%</b><small class="faint">ngân sách</small></div></ProgressRing>
        </template>
      </section>

      <section class="card kpi"><p class="lbl">Đang chạy</p>
        <Skeleton v-if="!state.objsLoaded" h="30px" w="90px" />
        <template v-else><p class="val"><AnimatedNumber :value="running.length" /><small> / {{ campItems.length }}</small></p><p class="sub faint">{{ campItems.length - running.length ? `${campItems.length - running.length} camp đang dừng` : 'Tất cả đang chạy' }}</p></template>
      </section>
      <section class="card kpi"><p class="lbl">Kết quả <InfoTip tip="results" /></p>
        <Skeleton v-if="kpiLoading" h="30px" w="90px" />
        <template v-else><p class="val"><AnimatedNumber :value="T.results" /></p><p class="sub faint">theo “{{ state.settings.resultAction || 'purchase' }}” · {{ today ? 'hôm nay' : rangeInfo.title.toLowerCase() }}</p></template>
      </section>
      <section class="card kpi"><p class="lbl">CPA trung bình <InfoTip tip="cpa" /></p>
        <Skeleton v-if="kpiLoading" h="30px" w="110px" />
        <template v-else><p class="val"><template v-if="avgCpa != null"><AnimatedNumber :value="avgCpa" :decimals="decimalsOf(currency)" /></template><template v-else>–</template></p><p class="sub faint">{{ mixed ? 'Khác loại tiền, chọn 1 tài khoản' : avgCpa != null ? 'Chi tiêu chia số kết quả' : 'Chưa có kết quả' }}</p></template>
      </section>
      <section class="card kpi"><p class="lbl">ROAS trung bình <InfoTip tip="roas" /></p>
        <Skeleton v-if="kpiLoading" h="30px" w="80px" />
        <template v-else><p class="val" :class="avgRoas != null && (avgRoas >= 2 ? 'ok' : avgRoas < 1 ? 'bad' : 'warn')">{{ avgRoas != null ? fmtDec(avgRoas) : '–' }}</p><p class="sub faint">{{ mixed ? 'Khác loại tiền, chọn 1 tài khoản' : 'Doanh thu chia chi tiêu' }}</p></template>
      </section>
    </div>

    <!-- Bảng chiến dịch -->
    <section class="card panel">
      <!-- Hàng 1: tìm kiếm, cột, hành động -->
      <div class="fbar">
        <div class="search"><Search :size="16" /><input ref="searchEl" v-model="q" class="input" :placeholder="`Tìm ${levelName}${multiAcc ? ' hoặc tài khoản' : ''}…`" aria-label="Tìm kiếm" /><kbd>/</kbd></div>
        <span class="sp" />
        <ColumnsMenu v-model="ov.columns" />
        <Popover v-model="actOpen" align="right" width="320px" label="Hành động hàng loạt">
          <template #trigger="{ toggle: tg }">
            <button type="button" class="acb" :class="{ on: actOpen }" :disabled="!state.objsLoaded" aria-haspopup="menu" :aria-expanded="actOpen" @click="tg">
              <Zap :size="16" /><span class="lb">{{ bulkText || 'Hành động' }}</span><ChevronDown :size="15" />
            </button>
          </template>
          <template #default="{ close }">
            <div class="menu" role="menu">
              <button type="button" class="mi" role="menuitem" @click="close(); bulkBudget = true"><Wallet :size="18" /><span><b>Đổi ngân sách hàng loạt</b><small>Lọc theo điều kiện rồi đổi nhiều mục một lúc</small></span></button>
              <button type="button" class="mi" role="menuitem" :disabled="!toOn" @click="close(); bulk(true)"><Power :size="18" /><span><b>Bật {{ toOn }} mục trong danh sách đã lọc</b><small>Chỉ các mục đang tắt, kể cả phần chưa bấm “Xem thêm”</small></span></button>
              <button type="button" class="mi danger" role="menuitem" :disabled="!toOff" @click="close(); bulk(false)"><Power :size="18" /><span><b>Tắt {{ toOff }} mục trong danh sách đã lọc</b><small>Chỉ các mục đang bật, kể cả phần chưa bấm “Xem thêm”</small></span></button>
            </div>
          </template>
        </Popover>
      </div>

      <!-- Hàng 2: cấp (chiến dịch / nhóm QC), trạng thái -->
      <div class="frow">
        <Segmented v-if="hasAdsets" v-model="ov.level" :options="levelOptions" size="sm" />
        <Segmented v-model="ov.status" :options="statusOptions" size="sm" />
        <span class="sp" />
        <select v-model="mobileSort" class="input msort" aria-label="Sắp xếp">
          <option v-for="[v, l] in mobileSortOptions" :key="v" :value="v">{{ l }}</option>
        </select>
      </div>

      <!-- Bộ lọc đang áp dụng -->
      <div v-if="chips.length" class="chips" aria-label="Bộ lọc đang áp dụng">
        <span v-for="c in chips" :key="c.k" class="chip"><small>{{ c.k }}</small><b :title="c.v">{{ c.v }}</b><button type="button" :aria-label="'Bỏ lọc ' + c.k" @click="c.clear()"><X :size="13" /></button></span>
        <button type="button" class="clr" @click="clearAll"><FilterX :size="14" />Xoá bộ lọc</button>
        <span class="cnt faint" aria-live="polite">Hiển thị <b class="num">{{ visible.length }}</b> / {{ inLevel.length }} {{ levelName }}</span>
      </div>

      <Callout v-if="ov.err && !today" tone="danger" class="stale">
        <b>Không tải được số liệu “{{ rangeInfo.title }}”:</b> {{ ov.err }}
        <Btn size="sm" :action="() => loadRange({ force: true })">Thử lại</Btn>
        <Btn size="sm" variant="ghost" @click="setSpec({ preset: 'today' })">Về hôm nay</Btn>
      </Callout>
      <Callout v-if="accErrors.length" tone="danger" class="stale">
        <b>Không tải được {{ accErrors.length }} tài khoản:</b>
        <template v-for="(x, i) in accErrors" :key="x.id">{{ i ? '; ' : ' ' }}{{ x.name }} ({{ x.error }})</template>.
        Các tài khoản khác vẫn hiện bình thường. Kiểm tra quyền của token với tài khoản này ở Cài đặt → Kết nối Facebook.
      </Callout>
      <Callout v-if="stalePanel" class="stale">
        <b>Facebook đang giới hạn số lần gọi</b>, nên đây là số liệu lúc {{ (rangeStale && ov.data.at ? new Date(ov.data.at) : state.objsAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }}, chưa phải số mới nhất.
        Tool tự tải lại{{ staleWait ? ` sau khoảng ${staleWait} phút` : ' khi được phép' }}, không cần bấm Làm mới. Lịch và rule vẫn chạy theo giờ; nếu Facebook từ chối thao tác, lỗi sẽ ghi ở Nhật ký.
      </Callout>

      <div v-if="!state.objsLoaded" class="skel"><div v-for="i in 5" :key="i"><Skeleton h="44px" r="12px" /></div></div>
      <EmptyState v-else-if="state.objsErr && !state.objs.length" :icon="PlugZap" tone="danger" title="Chưa tải được dữ liệu" :text="state.objsErr">
        <RouterLink to="/settings/connection"><Btn variant="primary">Kiểm tra kết nối</Btn></RouterLink>
      </EmptyState>
      <EmptyState v-else-if="!visible.length" :icon="chips.length ? SearchX : Megaphone" :title="chips.length ? 'Không có kết quả phù hợp' : 'Chưa có chiến dịch nào'" :text="chips.length ? 'Thử đổi từ khoá hoặc nới bộ lọc.' : 'Khi tài khoản có chiến dịch, chúng sẽ hiện ở đây.'">
        <Btn v-if="chips.length" variant="primary" :icon="FilterX" @click="clearAll">Xoá bộ lọc</Btn>
      </EmptyState>

      <div v-else class="tscroll" :class="{ dim: ov.loading && !today }">
        <div class="table" :class="{ hasacc: showAccCol }" :style="gridStyle">
          <div class="hd row">
            <span class="c-sw" />
            <span class="c-nm"><button type="button" class="sh" :class="{ on: sortKey === 'name' }" :title="sortTitle('name')" @click="sortBy('name')">{{ ov.level === 'campaign' ? 'Chiến dịch' : 'Nhóm quảng cáo' }}<component :is="sortIcon('name')" :size="13" /></button></span>
            <span v-if="showAccCol"><button type="button" class="sh" :class="{ on: sortKey === 'account' }" :title="sortTitle('account')" @click="sortBy('account')">Tài khoản<component :is="sortIcon('account')" :size="13" /></button></span>
            <span><button type="button" class="sh" :class="{ on: sortKey === 'delivery' }" :title="sortTitle('delivery')" @click="sortBy('delivery')">Phân phối<component :is="sortIcon('delivery')" :size="13" /></button></span>
            <div class="metrics">
              <span v-for="c in cols" :key="c.key" class="r">
                <button type="button" class="sh" :class="{ on: sortKey === c.key }" :title="sortTitle(c.key)" @click="sortBy(c.key)">{{ c.short || c.label }}<component :is="sortIcon(c.key)" :size="13" /></button>
                <InfoTip v-if="c.tip" :tip="c.tip" />
              </span>
            </div>
          </div>

          <TransitionGroup name="row" tag="div" :css="shown.length <= 40">
            <div v-for="it in shown" :key="it.o.id" class="row item" :class="{ off: it.o.status !== 'ACTIVE' }">
              <div class="c-sw"><Switch :model-value="it.o.status === 'ACTIVE'" :disabled="locked(it.o)" :title="locked(it.o) ? 'Camp đã lưu trữ hoặc bị từ chối, không thể bật' : ''" :loading="busy[it.o.id]" :label="'Bật/tắt ' + it.o.name" @update:model-value="(v) => toggle(it.o, v)" /></div>
              <div class="c-nm"><b :title="it.o.name">{{ it.o.name }}</b><small v-if="showAccCol" class="acc faint" :title="'Tài khoản quảng cáo ID ' + it.o.accountId">{{ accountLabel(it.o) }}</small><span v-if="it.o.learning && it.o.level === 'campaign'" class="bdg"><Badge tone="info" title="Có nhóm quảng cáo đang trong giai đoạn học: rule sẽ không đổi ngân sách camp này">Đang học</Badge></span></div>
              <div v-if="showAccCol" class="c-ac" :title="'Tài khoản quảng cáo ID ' + it.o.accountId"><b>{{ accountLabel(it.o) }}</b><small v-if="it.o.currency" class="faint">{{ it.o.currency }}</small></div>
              <div class="c-dl"><span class="dl" :class="deliveryOf(it.o).tone" :title="deliveryOf(it.o).label"><i />{{ deliveryOf(it.o).label }}</span></div>
              <div class="metrics">
                <div v-for="c in cols" :key="c.key" class="m r">
                  <span class="ml">{{ c.label }}</span>
                  <BudgetCell v-if="c.key === 'budget'" :o="it.o" />
                  <template v-else-if="c.key === 'spend'">
                    <span class="num sp">{{ money(it.m.spend, cur(it.o)) }}</span>
                    <i v-if="today && it.o.dailyBudget" class="mini"><b :style="{ width: Math.min(100, (it.m.spend / it.o.dailyBudget) * 100) + '%' }" /></i>
                  </template>
                  <template v-else-if="c.key === 'roas'"><Badge v-if="roasTone(it.m)" :tone="roasTone(it.m)" class="num">{{ fmtDec(it.m.roas) }}</Badge><span v-else class="faint">–</span></template>
                  <span v-else class="num" :class="{ faint: cellValue(c.key, it) == null }">{{ cellText(c, cellValue(c.key, it), cur(it.o)) }}</span>
                </div>
              </div>
            </div>
          </TransitionGroup>

          <div v-if="moreCount > 0" class="more">
            <Btn :icon="ChevronsDown" @click="limit += pageSize()">Xem thêm {{ moreCount }}</Btn>
            <span class="faint" aria-live="polite">Đang hiển thị <b class="num">{{ shown.length }}</b> / <b class="num">{{ visible.length }}</b> {{ levelName }}</span>
          </div>

          <div class="tot row">
            <span class="t-l" :style="{ gridColumn: '1 / span ' + lead }"><b>Tổng</b> · {{ visible.length }} {{ levelName }}<small v-if="visMixed" class="faint"> · khác loại tiền</small></span>
            <div class="metrics"><span v-for="c in cols" :key="c.key" class="m r"><span class="ml">{{ c.label }}</span><b class="num">{{ totCell(c) }}</b></span></div>
          </div>
        </div>
      </div>

      <div v-if="state.objsLoaded && visible.length" class="foot faint">
        <span class="cnt"><b class="num">{{ visible.length }}</b> mục</span>
        <span class="mfoot">Chi tiêu <template v-for="(g, i) in footSpend" :key="g.currency"><template v-if="i"> + </template><b class="num">{{ money(g.s, g.currency) }}</b><template v-if="footSpend.length > 1"> {{ g.currency }}</template></template></span>
        <span class="mfoot">Kết quả <b class="num">{{ fmt(footResults) }}</b></span>
        <span v-if="state.objsMeta && state.objsMeta.usage" class="right" :title="`Mức dùng lượt gọi Facebook API (${state.objsMeta.usage.tier || 'không rõ hạng'}). Tới 100% thì Facebook tạm chặn; tool tự giãn thời gian làm mới khi vượt 60%.`">
          API Facebook <b class="num" :class="{ warnc: state.objsMeta.usage.pct >= 60 }">{{ state.objsMeta.usage.pct }}%</b></span>
        <span v-if="state.objsAt" :class="{ right: !(state.objsMeta && state.objsMeta.usage) }">Số liệu lúc {{ (rangeStale || (!today && ov.data && ov.data.at) ? new Date(ov.data.at) : state.objsAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }}</span>
      </div>
    </section>
    <BulkBudget v-model="bulkBudget" :level="ov.level" :account="singleAcc" />
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
.big.cur { font-size: 30px; display: flex; align-items: baseline; flex-wrap: wrap; }
.big.cur em { font-style: normal; font-size: 13px; font-weight: 500; letter-spacing: 0; margin-left: 12px; }
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

/* ----- Thanh bộ lọc ----- */
.gbar { position: sticky; top: 0; z-index: 35; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: -6px -10px 14px; padding: 8px 10px; border-radius: 16px; background: color-mix(in srgb, var(--bg) 84%, transparent); backdrop-filter: blur(12px) saturate(1.2); -webkit-backdrop-filter: blur(12px) saturate(1.2); }
.gnote { font-size: 13px; flex: 1 1 260px; min-width: 0; } .gnote b { color: var(--text-2); }
.panel { overflow: clip; }
.fbar { display: flex; align-items: center; gap: 10px; padding: 14px 16px 10px; flex-wrap: wrap; }
.fbar .sp, .frow .sp { flex: 1; }
.search { position: relative; flex: 1 1 200px; min-width: 170px; max-width: 340px; }
.search svg { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.search .input { padding: 0 40px 0 38px; height: 42px; }
.search kbd { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); }
.acb {
  display: inline-flex; align-items: center; gap: 8px; min-height: 42px; padding: 6px 13px; border-radius: 12px; cursor: pointer;
  background: var(--surface); border: 1px solid var(--border-strong); color: var(--text-2); font: inherit; font-size: 14px; font-weight: 600; box-shadow: var(--shadow-sm);
  transition: border-color .15s, box-shadow .15s, color .15s;
}
.acb:hover:not(:disabled), .acb.on { border-color: var(--accent); color: var(--accent); }
.acb.on { box-shadow: 0 0 0 4px var(--accent-soft); }
.acb:disabled { opacity: .55; cursor: not-allowed; }
.menu { padding: 6px; display: grid; gap: 2px; min-width: 300px; }
.mi { display: flex; gap: 12px; align-items: flex-start; text-align: left; width: 100%; border: 0; background: none; color: var(--text); font: inherit; padding: 10px 11px; border-radius: 10px; cursor: pointer; }
.mi:hover:not(:disabled) { background: var(--surface-2); }
.mi:disabled { opacity: .45; cursor: not-allowed; }
.mi svg { flex: none; margin-top: 2px; color: var(--accent); }
.mi span { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.mi b { font-size: 14px; font-weight: 650; } .mi small { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.mi.danger svg { color: var(--danger); } .mi.danger:hover:not(:disabled) { background: var(--danger-soft); }
.frow { display: flex; align-items: center; gap: 12px; padding: 0 16px 12px; flex-wrap: wrap; border-bottom: 1px solid var(--border); }
.msort { display: none; width: auto; padding: 7px 10px; font-size: 13.5px; }
.chips { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px 16px; border-bottom: 1px solid var(--border); background: var(--surface-2); }
.chip { display: inline-flex; align-items: center; gap: 7px; padding: 4px 5px 4px 11px; border-radius: 99px; background: var(--accent-soft); color: var(--accent); font-size: 13px; max-width: 100%; }
.chip small { font-size: 12px; opacity: .8; } .chip b { font-weight: 650; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chip button { display: grid; place-items: center; width: 20px; height: 20px; border: 0; border-radius: 50%; background: transparent; color: inherit; cursor: pointer; }
.chip button:hover { background: var(--accent); color: var(--on-accent); }
.clr { display: inline-flex; align-items: center; gap: 6px; border: 0; background: none; color: var(--text-2); font: inherit; font-size: 13px; font-weight: 600; padding: 4px 8px; border-radius: 8px; cursor: pointer; }
.clr:hover { background: var(--surface-3); color: var(--text); }
.chips .cnt { margin-left: auto; font-size: 13px; } .chips .cnt b { color: var(--text); }
.skel { padding: 16px 18px; display: grid; gap: 12px; }
.stale { margin: 14px 16px 0; }
.stale .btn { margin-left: 8px; }

/* ----- Bảng: máy tính = bảng cuộn được (tiêu đề, 2 cột đầu, hàng tổng cố định); điện thoại = thẻ ----- */
.tscroll { overflow: auto; max-height: min(74vh, 780px); transition: opacity .2s; overscroll-behavior: contain; }
.tscroll.dim { opacity: .5; pointer-events: none; }
.table { min-width: var(--minw); }
.row { display: grid; grid-template-columns: var(--cols); align-items: center; padding: 0 18px 0 0; gap: 6px; }
.metrics { display: contents; }
/* tiêu đề cột bấm được để sắp xếp */
.sh { display: inline-flex; align-items: center; gap: 5px; border: 0; background: none; padding: 4px 6px; margin: -4px -6px; border-radius: 7px; font: inherit; color: inherit; cursor: pointer; white-space: nowrap; }
.sh svg { opacity: .45; flex: none; }
.sh:hover { color: var(--text); background: var(--surface-3); } .sh:hover svg { opacity: .8; }
.sh.on { color: var(--accent); } .sh.on svg { opacity: 1; }
.hd { position: sticky; top: 0; z-index: 4; padding-top: 11px; padding-bottom: 11px; font-size: 12.5px; font-weight: 650; color: var(--text-3); background: var(--surface-2); border-bottom: 1px solid var(--border); letter-spacing: .01em; }
.hd .r { display: inline-flex; justify-content: flex-end; align-items: center; gap: 2px; }
.item { min-height: 68px; border-bottom: 1px solid var(--border); background: var(--surface); transition: background .15s; }
.item:hover { background: var(--surface-2); }
/* 2 cột đầu đứng yên khi cuộn ngang */
.c-sw { position: sticky; left: 0; z-index: 2; padding-left: 18px; background: inherit; align-self: stretch; display: flex; align-items: center; }
.c-nm { position: sticky; left: 70px; z-index: 2; background: inherit; align-self: stretch; box-shadow: 1px 0 0 var(--border); }
.hd .c-sw, .hd .c-nm { z-index: 5; }
.c-nm { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 3px; padding: 12px 8px 12px 0; }
.hd .c-nm { flex-direction: row; align-items: center; padding: 0; }
.c-nm b { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 15px; font-weight: 620; }
.item.off .c-nm b { color: var(--text-2); }
.bdg { display: flex; gap: 6px; flex-wrap: wrap; }
.c-nm .acc { display: none; font-size: 12.5px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: -2px; }
.c-ac { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; padding: 12px 0; }
.c-ac b { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13.5px; font-weight: 600; color: var(--text-2); }
.c-ac small { font-size: 11.5px; padding: 1px 7px; border-radius: 6px; background: var(--surface-3); }
/* cột Phân phối: chấm màu + chữ như Ads Manager */
.c-dl { min-width: 0; }
.dl { display: inline-flex; align-items: center; gap: 7px; font-size: 13.5px; color: var(--text-2); max-width: 100%; }
.dl i { width: 9px; height: 9px; border-radius: 50%; background: var(--text-3); opacity: .6; flex: none; }
.dl.success { color: var(--text); } .dl.success i { background: var(--success); opacity: 1; }
.dl.info i { background: var(--info); opacity: 1; } .dl.warning i { background: var(--warning); opacity: 1; } .dl.danger { color: var(--danger); } .dl.danger i { background: var(--danger); opacity: 1; }
.r { text-align: right; justify-self: end; }
.m { display: flex; flex-direction: column; align-items: flex-end; gap: 3px; min-width: 0; }
.ml { display: none; font-size: 12px; color: var(--text-3); }
.sp { font-weight: 600; }
.mini { width: 74px; height: 4px; border-radius: 9px; background: var(--surface-3); overflow: hidden; }
.mini b { display: block; height: 100%; background: var(--accent-grad); }
/* hàng tổng cố định ở đáy bảng */
.more { position: sticky; left: 0; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 16px 18px; border-bottom: 1px solid var(--border); font-size: 13.5px; }
.more b { color: var(--text); }
.tot { position: sticky; bottom: 0; z-index: 4; min-height: 52px; background: var(--surface-2); border-top: 1px solid var(--border-strong); font-size: 14px; }
.tot .t-l { position: sticky; left: 0; z-index: 5; padding-left: 18px; background: inherit; align-self: stretch; display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.tot b { font-weight: 700; }
.foot { display: flex; gap: 22px; flex-wrap: wrap; padding: 13px 20px; border-top: 1px solid var(--border); font-size: 13.5px; }
.foot b { color: var(--text); } .foot .right { margin-left: auto; } .foot b.warnc { color: var(--warning); }
.mfoot { display: none; }
.row-enter-active, .row-leave-active { transition: all .3s var(--ease); }
.row-enter-from, .row-leave-to { opacity: 0; transform: translateX(-10px); }

@media (max-width: 1100px) { .hero { grid-column: span 12; grid-row: auto; } .kpi { grid-column: span 6; } }
@media (max-width: 860px) {
  .tscroll { max-height: none; overflow: visible; }
  .table { min-width: 0; }
  .hd, .tot { display: none; }
  .more { position: static; justify-content: center; text-align: center; padding: 14px 16px 18px; }
  .more .btn { width: 100%; }
  .row, .table.hasacc .row { grid-template-columns: auto minmax(0, 1fr); padding: 14px 16px; gap: 10px 14px; }
  .c-sw, .c-nm { position: static; box-shadow: none; padding-left: 0; background: none; }
  .c-nm { padding: 0; }
  .c-nm .acc { display: block; }
  .c-ac { display: none; }
  .metrics { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(2, 1fr); gap: 12px; padding-top: 12px; border-top: 1px dashed var(--border); }
  .m { align-items: flex-start; } .r { justify-self: start; text-align: left; }
  .ml { display: block; }
  .c-dl { grid-column: 2; margin-top: -6px; }
  .msort { display: block; }
  .item { min-height: 0; }
  .big { font-size: 34px; }
  .hero { flex-direction: column; align-items: flex-start; }
  .kpi { grid-column: span 6; }
  .frow .sp { display: none; }
  .mfoot { display: inline; }
}
@media (max-width: 640px) {
  .fbar { gap: 8px; }
  .gbar > :deep(.pop-root) { flex: 1 1 100%; }
  .gbar { position: static; margin: 0 0 14px; padding: 0; background: none; backdrop-filter: none; }
  .search { max-width: none; flex: 1 1 100%; }
  .fbar .sp { display: none; }
  .chips .cnt { margin-left: 0; width: 100%; }
}
</style>
