<script setup>
// Đổi ngân sách hàng loạt, giao diện riêng (không dùng khung chọn chung với Lịch):
//  - trái: thanh lọc gọn (cấp, tìm tên, trạng thái, Bộ lọc, khoảng ngày), "Chọn nhanh" theo kết quả, danh sách kèm số liệu
//  - phải (cố định, luôn thấy): tổng số liệu của các mục đã chọn, ngân sách mới, tổng ngân sách trước/sau, nút Đổi
//  - điện thoại: mỗi mục là một thẻ; thanh dưới cùng hiện số đã chọn, bấm "Tiếp" để sang bước nhập ngân sách
// Số liệu và khoảng ngày, bộ cột dùng chung với Tổng quan. Mỗi mục đi qua đúng API đổi ngân sách thủ công
// nên được kiểm tra, ghi Nhật ký và hoàn tác được như khi sửa từng camp.
import { ref, reactive, computed, watch } from 'vue'
import { Check, Square, Wallet, Play, Search, SlidersHorizontal, RefreshCw, ArrowUp, ArrowDown, ArrowUpDown, ChevronLeft, X } from 'lucide-vue-next'
import { state, loadObjs, setObjBudget } from '../stores/app'
import { toast, confirm } from '../stores/ui'
import { fmt, fmtDec } from '../lib/format'
import { CONDS, STATUS_FILTERS, parseMoney, readFilter, readAction, budgetChange, matchFilter } from '../lib/bulkBudget'
import { DELIVERY, deliveryMap } from '../lib/delivery'
import { accountLabel } from '../lib/accounts'
import { ov, rangeInfo, rangeReady, loadRange, setSpec, itemOf, todayISO } from '../stores/overview'
import { colOf, cellText, money } from '../lib/overviewColumns'
import { summarize, avgCpa, cpaTone, oneCurrency, QUICK, quickMatches } from '../lib/pick'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Badge from './Badge.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'
import Popover from './Popover.vue'
import DateRangePicker from './DateRangePicker.vue'
import ColumnsMenu from './ColumnsMenu.vue'

const props = defineProps({ modelValue: Boolean, level: { type: String, default: 'campaign' }, account: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])

const blankFilter = () => ({ level: 'campaign', op: 'any', x: '', y: '', name: '', status: 'all', onlyRunning: false, account: '' })
const filter = reactive(blankFilter())
const act = reactive({ mode: 'percent', value: '' })
const selected = ref([])
const run = ref(null) // { total, done, ok, fails: [{ name, msg }], remaining: [], rateLimited, running, stop }
const step = ref('list') // điện thoại: 'list' (chọn) | 'budget' (nhập ngân sách)
const fltOpen = ref(false)
const sort = ref({ key: '', dir: 'desc' })

watch(() => props.modelValue, (open) => {
  if (!open) return
  Object.assign(filter, blankFilter(), { level: props.level, account: props.account })
  Object.assign(act, { mode: 'percent', value: '' })
  selected.value = []; run.value = null; step.value = 'list'; sort.value = { key: '', dir: 'desc' }
  if (!rangeReady.value) loadRange({ bg: true })
})
watch(() => filter.status, (v) => { filter.onlyRunning = v === 'running' })

const levelName = computed(() => (filter.level === 'adset' ? 'nhóm QC' : 'chiến dịch'))
const hasAdsets = computed(() => state.objs.some((o) => o.level === 'adset'))
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
const campName = computed(() => { const m = {}; for (const o of state.objs) if (o.level === 'campaign') m[o.id] = o.name; return m })
const dm = computed(() => deliveryMap(state.objs))
const deliveryOf = (o) => DELIVERY[dm.value[o.id]] || DELIVERY.off
const statusOpts = Object.entries(STATUS_FILTERS).map(([value, label]) => ({ value, label }))
const modeOpts = [{ value: 'set', label: 'Đặt bằng' }, { value: 'percent', label: 'Theo %' }, { value: 'add', label: 'Cộng/trừ' }]
const typed = (v) => v != null && v !== ''
const moneyHint = (v) => { const n = parseMoney(v); return typed(v) && Number.isFinite(n) ? fmt(n) : '' }

// ----- Bộ lọc (trong nút "Bộ lọc": ngân sách, tài khoản) -----
const fr = computed(() => readFilter(filter))
const fltErr = computed(() => (typed(filter.x) || typed(filter.y) ? fr.value.errors.x || fr.value.errors.y || '' : ''))
const fltCount = computed(() => (filter.op !== 'any' ? 1 : 0) + (filter.account ? 1 : 0))
const clearFlt = () => Object.assign(filter, { op: 'any', x: '', y: '', account: '' })
const reloading = ref(false)
async function reload() { reloading.value = true; try { await Promise.all([loadObjs(true), loadRange({ force: true })]) } finally { reloading.value = false } }

// ----- Danh sách -----
const matched = computed(() => (Object.keys(fr.value.errors).length ? null : matchFilter(state.objs, fr.value.filter)))
const cboHidden = computed(() => (matched.value ? matched.value.filter((o) => o.dailyBudget == null).length : 0))
const mcols = computed(() => ov.columns.filter((k) => k !== 'budget').map(colOf))
const items = computed(() => (matched.value || []).filter((o) => o.dailyBudget != null).map(itemOf))
const avg = computed(() => (rangeReady.value ? avgCpa(items.value) : null))
const rangeTitle = computed(() => (rangeInfo.value.today ? 'hôm nay' : rangeInfo.value.title.toLowerCase()))

const collator = new Intl.Collator('vi', { numeric: true, sensitivity: 'base' })
const sortVal = (k, it) => (k === 'name' ? it.o.name : k === 'budget' ? it.o.dailyBudget : it.m[k])
const rows = computed(() => {
  const list = [...items.value]
  const { key, dir } = sort.value
  if (key && (key === 'name' || key === 'budget' || mcols.value.some((c) => c.key === key))) {
    list.sort((a, b) => {
      const x = sortVal(key, a), y = sortVal(key, b)
      if (key !== 'name' && (x == null || y == null)) return x == null ? (y == null ? 0 : 1) : -1 // ô trống luôn nằm cuối
      const r = key === 'name' ? collator.compare(x, y) : x - y
      return dir === 'asc' ? r : -r
    })
  }
  return list
})
function sortBy(k) {
  const s = sort.value
  sort.value = s.key === k ? { key: k, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key: k, dir: k === 'name' ? 'asc' : (colOf(k) && colOf(k).first) || 'desc' }
}
const sortIcon = (k) => (sort.value.key !== k ? ArrowUpDown : sort.value.dir === 'asc' ? ArrowUp : ArrowDown)
// Chỉ VẼ một phần danh sách (vài nghìn mục thì vẽ hết sẽ giật); chọn tất cả / đếm vẫn tính trên toàn bộ
const pageSize = () => (window.matchMedia('(max-width: 760px)').matches ? 40 : 100)
const cap = ref(100)
watch([() => filter.level, () => filter.name, () => filter.status, () => filter.op, () => filter.x, () => filter.y, () => filter.account, sort], () => { cap.value = pageSize() }, { deep: true })
const shown = computed(() => rows.value.slice(0, cap.value))
const moreRows = computed(() => Math.min(pageSize(), rows.value.length - shown.value.length))
// Mọi dòng cùng một khuôn cột và cùng bề rộng tối thiểu nên luôn thẳng hàng; hẹp hơn thì cuộn ngang
const gridCols = computed(() => `22px minmax(220px, 1fr) repeat(${mcols.value.length}, minmax(84px, 104px)) 210px`)
const gridMin = computed(() => `${22 + 220 + mcols.value.length * 84 + 210 + (mcols.value.length + 3) * 14 + 44}px`)

// Bỏ chọn mục không còn hiện (đổi bộ lọc) để không đổi mục bạn không nhìn thấy
watch(rows, (r) => {
  const ids = new Set(r.map((x) => x.o.id))
  if (selected.value.some((id) => !ids.has(id))) selected.value = selected.value.filter((id) => ids.has(id))
})

// ----- Chọn -----
const sel = computed(() => new Set(selected.value))
const allOn = computed(() => rows.value.length > 0 && rows.value.every((r) => sel.value.has(r.o.id)))
const someOn = computed(() => selected.value.length > 0 && !allOn.value)
const toggle = (id) => { selected.value = sel.value.has(id) ? selected.value.filter((x) => x !== id) : [...selected.value, id] }
const toggleAll = () => { selected.value = allOn.value ? [] : rows.value.map((r) => r.o.id) }
const quick = computed(() => (rangeReady.value ? quickMatches(items.value, avg.value) : {}))
const quickList = computed(() => QUICK.map((q) => ({ ...q, ids: quick.value[q.key] || [] })).filter((q) => q.ids.length))
const quickOn = (q) => q.ids.length === selected.value.length && q.ids.every((id) => sel.value.has(id))
const pickQuick = (q) => { selected.value = quickOn(q) ? [] : [...q.ids] }

// ----- Ô số liệu -----
const roasTone = (m) => (!m.spend || m.roas == null ? null : m.roas >= 2 ? 'success' : m.roas < 1 ? 'danger' : 'warning')
const curOf = (o) => o.currency || 'VND'

// ----- Ngân sách mới -----
const action = computed(() => readAction(act))
const actReady = computed(() => !Object.keys(action.value.errors).length)
const actErr = computed(() => (act.value !== '' ? action.value.errors.value || '' : ''))
const valueHint = computed(() => (act.mode === 'percent' ? `Số âm để giảm, vd -20${act.value !== '' && !actErr.value ? ` → ${act.value}%` : ''}` : act.mode === 'add' ? `Số âm để trừ, vd -50k${moneyHint(act.value) ? ` → ${moneyHint(act.value)}` : ''}` : `Gõ được 500k, 1,5tr hoặc 500.000${moneyHint(act.value) ? ` → ${moneyHint(act.value)}` : ''}`))
const changeOf = (o) => (actReady.value ? budgetChange(o, action.value.action) : null)
const pct = (o, ch) => `${ch.to > o.dailyBudget ? '+' : ''}${Math.round((ch.to / o.dailyBudget - 1) * 100)}%`
const isBig = (o, ch) => ch.to / o.dailyBudget >= 2 || ch.to / o.dailyBudget <= 0.5

const chosen = computed(() => rows.value.filter((r) => sel.value.has(r.o.id)))
const chosenSum = computed(() => summarize(chosen.value))
const chosenCur = computed(() => (chosen.value.length && oneCurrency(chosen.value) ? curOf(chosen.value[0].o) : null))
const toRun = computed(() => (actReady.value ? chosen.value.map((r) => ({ o: r.o, ch: budgetChange(r.o, action.value.action) })).filter((r) => r.ch.kind === 'change') : []))
const skipped = computed(() => (actReady.value ? chosen.value.length - toRun.value.length : 0))
const budgetNow = computed(() => chosen.value.reduce((t, r) => t + r.o.dailyBudget, 0))
const sumTo = computed(() => toRun.value.reduce((t, r) => t + r.ch.to, 0) + chosen.value.filter((r) => !toRun.value.some((x) => x.o.id === r.o.id)).reduce((t, r) => t + r.o.dailyBudget, 0))
const bigCount = computed(() => toRun.value.filter((r) => isBig(r.o, r.ch)).length)
const learningCount = computed(() => toRun.value.filter((r) => r.o.learning).length)
const hint = computed(() => (!chosen.value.length ? '' // thẻ "Đã chọn" phía trên đã hướng dẫn
  : !actReady.value ? 'Nhập ngân sách mới' : !toRun.value.length ? 'Các mục đã chọn đều không cần đổi' : ''))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function execute(list) {
  run.value = { total: list.length, done: 0, ok: 0, fails: [], remaining: [], rateLimited: false, running: true, stop: false }
  const r = run.value // bản reactive: giao diện cập nhật tiến độ, nút Dừng đặt r.stop
  for (let k = 0; k < list.length; k++) {
    if (r.stop) { r.remaining = list.slice(k); break }
    const it = list[k]
    try {
      await setObjBudget(it.o, it.to)
      r.ok++
    } catch (e) {
      if (e.data && e.data.rateLimited) { r.rateLimited = true; r.remaining = list.slice(k); break } // bị Facebook giới hạn: dừng, giữ phần còn lại để chạy tiếp sau
      r.fails.push({ name: it.o.name, msg: e.message })
    }
    r.done++
    if (k < list.length - 1) await sleep(400) // giãn nhịp để đỡ chạm giới hạn số lần gọi
  }
  r.running = false
  toast(r.remaining.length ? `Đã đổi ${r.ok}/${r.total} mục, còn ${r.remaining.length} mục chưa đổi` : `Đã đổi ngân sách ${r.ok} ${levelName.value}${r.fails.length ? `, ${r.fails.length} lỗi` : ''}`, r.fails.length || r.remaining.length ? 'error' : 'success')
}

async function start() {
  const list = toRun.value
  if (!list.length) return toast(hint.value || `Chưa chọn ${levelName.value} nào`, 'error')
  const from = list.reduce((t, r) => t + r.o.dailyBudget, 0), to = list.reduce((t, r) => t + r.ch.to, 0)
  const msg = `Tổng ngân sách/ngày của ${list.length} ${levelName.value}: ${fmt(from)} → ${fmt(to)}.`
    + (skipped.value ? ` Bỏ qua ${skipped.value} mục đã đúng mức hoặc không hợp lệ.` : '')
    + (bigCount.value ? ` ${bigCount.value} mục thay đổi từ gấp đôi hoặc giảm một nửa trở lên, Facebook có thể cho học lại.` : '')
    + (state.settings.mock ? '' : ' Thay đổi áp dụng thật lên Facebook ngay.')
  if (!await confirm(`Đổi ngân sách ${list.length} ${levelName.value}?`, msg, { ok: 'Đổi ngân sách' })) return
  await execute(list.map((r) => ({ o: r.o, to: r.ch.to })))
}
const resume = () => execute(run.value.remaining)
const again = () => { run.value = null; selected.value = []; step.value = 'list' }
const close = () => { if (run.value && run.value.running) run.value.stop = true; emit('update:modelValue', false) }
</script>

<template>
  <Modal :model-value="modelValue" fill title="Đổi ngân sách hàng loạt" subtitle="Xem kết quả từng mục, tick chọn, rồi nhập ngân sách mới." width="1320px" @update:model-value="(v) => !v && close()">
    <div v-if="!run" class="bb" :class="{ 'st-budget': step === 'budget' }">
      <!-- Thanh lọc -->
      <div class="tb">
        <Segmented v-if="hasAdsets" v-model="filter.level" class="lv" :options="[{ value: 'campaign', label: 'Chiến dịch' }, { value: 'adset', label: 'Nhóm quảng cáo' }]" size="sm" />
        <label class="search"><Search :size="15" /><input v-model="filter.name" placeholder="Tìm theo tên…" title="Nhiều từ khoá cách nhau bằng dấu phẩy, vd: sale, lead" aria-label="Tìm theo tên" /><button v-if="filter.name" type="button" aria-label="Xoá" @click="filter.name = ''"><X :size="14" /></button></label>
        <Segmented v-model="filter.status" class="stt" :options="statusOpts" size="sm" />
        <Popover v-model="fltOpen" width="320px" label="Bộ lọc">
          <template #trigger="{ toggle: tg }">
            <button type="button" class="tbtn" :class="{ on: fltOpen || fltCount }" @click="tg"><SlidersHorizontal :size="15" /><span class="lb">Bộ lọc</span><em v-if="fltCount" class="num">{{ fltCount }}</em></button>
          </template>
          <div class="fpop">
            <p class="ph">Ngân sách/ngày hiện tại</p>
            <select v-model="filter.op" class="input" aria-label="Điều kiện ngân sách"><option v-for="(c, k) in CONDS" :key="k" :value="k">{{ c.label }}</option></select>
            <div v-if="filter.op !== 'any'" class="two">
              <span class="mi"><input v-model="filter.x" class="input" inputmode="decimal" placeholder="vd 100k" aria-label="Mức ngân sách" /><small class="faint">{{ moneyHint(filter.x) }}</small></span>
              <span v-if="filter.op === 'between'" class="mi"><input v-model="filter.y" class="input" inputmode="decimal" placeholder="vd 300k" aria-label="Mức thứ hai" /><small class="faint">{{ moneyHint(filter.y) }}</small></span>
            </div>
            <p v-if="fltErr" class="ferr">{{ fltErr }}</p>
            <template v-if="multiAcc">
              <p class="ph">Tài khoản quảng cáo</p>
              <select v-model="filter.account" class="input" aria-label="Tài khoản quảng cáo"><option value="">Mọi tài khoản ({{ accounts.length }})</option><option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option></select>
            </template>
            <div class="fact">
              <button type="button" class="lnk" :disabled="!fltCount" @click="clearFlt">Xoá bộ lọc</button>
              <button type="button" class="lnk rl" :disabled="reloading || state.objsLoading" @click="reload"><RefreshCw :size="13" :class="{ spin: reloading }" />Tải lại từ Facebook</button>
            </div>
          </div>
        </Popover>
        <span class="grow" />
        <DateRangePicker :model-value="ov.spec" :today="todayISO()" :loading="ov.loading" @update:model-value="setSpec" />
        <span class="cm"><ColumnsMenu v-model="ov.columns" /></span>
      </div>
      <div v-if="quickList.length" class="quick">
        <span class="faint ql">Chọn nhanh:</span>
        <button v-for="q in quickList" :key="q.key" type="button" class="chip" :class="{ on: quickOn(q) }" :title="q.label" @click="pickQuick(q)"><span class="full">{{ q.label }}</span><span class="short">{{ q.short }}</span> <i class="num">({{ q.ids.length }})</i></button>
      </div>
      <p v-if="ov.err" class="ferr pad">Không tải được số liệu “{{ rangeInfo.title }}”: {{ ov.err }}</p>

      <div class="body">
        <!-- Danh sách -->
        <section class="main">
          <div class="scroll" :style="{ '--gt': gridCols, '--mw': gridMin }">
            <div v-if="rows.length" class="row hd">
              <input type="checkbox" :checked="allOn" :indeterminate="someOn" aria-label="Chọn tất cả" title="Chọn / bỏ chọn tất cả mục đang hiện" @change="toggleAll" />
              <button type="button" class="sh" :class="{ on: sort.key === 'name' }" @click="sortBy('name')">{{ filter.level === 'adset' ? 'Nhóm quảng cáo' : 'Chiến dịch' }}<component :is="sortIcon('name')" :size="12" /></button>
              <span class="mets"><button v-for="c in mcols" :key="c.key" type="button" class="sh ra" :class="{ on: sort.key === c.key }" :title="c.menu || c.label" @click="sortBy(c.key)">{{ c.short || c.label }}<component :is="sortIcon(c.key)" :size="12" /></button></span>
              <button type="button" class="sh ra" :class="{ on: sort.key === 'budget' }" @click="sortBy('budget')">Ngân sách/ngày<component :is="sortIcon('budget')" :size="12" /></button>
            </div>
            <label v-for="r in shown" :key="r.o.id" class="row it" :class="{ on: sel.has(r.o.id) }">
              <input type="checkbox" :checked="sel.has(r.o.id)" @change="toggle(r.o.id)" />
              <span class="nm"><b :title="r.o.name">{{ r.o.name }}</b>
                <small class="dl" :class="deliveryOf(r.o).tone"><i />{{ deliveryOf(r.o).label }}<template v-if="r.o.level === 'adset' && campName[r.o.campaignId]"> · <span :title="campName[r.o.campaignId]">{{ campName[r.o.campaignId] }}</span></template><template v-if="multiAcc && !filter.account"> · {{ accountLabel(r.o) }}</template></small></span>
              <span class="mets">
                <span v-for="c in mcols" :key="c.key" class="mc">
                  <small class="ml">{{ c.short || c.label }}</small>
                  <span v-if="!rangeReady" class="faint">…</span>
                  <template v-else-if="c.key === 'roas'"><Badge v-if="roasTone(r.m)" :tone="roasTone(r.m)" class="num">{{ fmtDec(r.m.roas) }}</Badge><span v-else class="faint">–</span></template>
                  <span v-else-if="c.key === 'cpa'" class="num cpa" :class="{ faint: r.m.cpa == null }"><i v-if="cpaTone(r.m.cpa, avg)" :class="cpaTone(r.m.cpa, avg)" :title="`CPA trung bình danh sách: ${money(avg, curOf(r.o))}`" />{{ cellText(c, r.m.cpa, curOf(r.o)) }}</span>
                  <span v-else class="num" :class="{ faint: r.m[c.key] == null || (c.key !== 'spend' && !r.m.spend) }">{{ cellText(c, r.m[c.key], curOf(r.o)) }}</span>
                </span>
              </span>
              <span class="bud num">
                <small class="ml">Ngân sách/ngày</small>
                <template v-if="sel.has(r.o.id) && changeOf(r.o) && changeOf(r.o).kind === 'change'"><span class="old">{{ fmt(r.o.dailyBudget) }}</span><span class="arr">→</span><b>{{ fmt(changeOf(r.o).to) }}</b><small class="pc" :class="{ up: changeOf(r.o).to > r.o.dailyBudget, bigc: isBig(r.o, changeOf(r.o)) }">{{ pct(r.o, changeOf(r.o)) }}</small></template>
                <template v-else-if="sel.has(r.o.id) && changeOf(r.o) && changeOf(r.o).kind === 'invalid'"><span>{{ fmt(r.o.dailyBudget) }}</span><small class="badc">không hợp lệ</small></template>
                <span v-else>{{ fmt(r.o.dailyBudget) }}</span>
              </span>
            </label>
            <button v-if="moreRows > 0" type="button" class="morelnk" @click="cap += pageSize()">Hiển thị thêm {{ moreRows }} <small>(đang hiển thị {{ shown.length }} / {{ rows.length }})</small></button>
            <p v-if="!matched" class="empty faint">Nhập mức ngân sách trong “Bộ lọc”, hoặc chọn “Bất kỳ”.</p>
            <p v-else-if="!rows.length" class="empty faint">Không có {{ levelName }} nào khớp. Thử nới bộ lọc.</p>
          </div>
          <div class="foot faint">
            <span v-if="matched"><b class="num">{{ rows.length }}</b> {{ levelName }} khớp</span>
            <span v-if="avg != null">CPA trung bình <b class="num">{{ money(avg, curOf(rows[0].o)) }}</b></span>
            <span>{{ rangeReady ? `Số liệu ${rangeTitle}` : 'Đang tải số liệu…' }} · ngân sách và trạng thái là hiện tại</span>
            <span v-if="cboHidden">Ẩn {{ cboHidden }} mục dùng ngân sách chiến dịch (CBO)</span>
          </div>
          <!-- điện thoại: thanh dưới -->
          <div class="mbar">
            <div class="mt">
              <span>Đã chọn {{ chosen.length }} {{ levelName }}<template v-if="chosen.length && chosenSum.cpa != null && chosenCur"> · CPA TB {{ money(chosenSum.cpa, chosenCur) }}</template></span>
              <b v-if="chosen.length" class="num">{{ toRun.length ? `${fmt(budgetNow)} → ${fmt(sumTo)}` : fmt(budgetNow) }}/ngày</b>
            </div>
            <Btn variant="primary" :disabled="!chosen.length" @click="step = 'budget'">Tiếp</Btn>
          </div>
        </section>

        <!-- Cột phải: đã chọn + ngân sách mới -->
        <aside class="side">
          <button type="button" class="back lnk" @click="step = 'list'"><ChevronLeft :size="16" />Quay lại danh sách</button>
          <h4>Đã chọn <span class="cnt" :class="{ on: chosen.length }">{{ chosen.length }} {{ levelName }}</span></h4>
          <div v-if="chosen.length" class="card kp">
            <div><small>Chi tiêu {{ rangeTitle }}</small><b class="num">{{ chosenCur ? money(chosenSum.spend, chosenCur) : '–' }}</b></div>
            <div><small>Kết quả</small><b class="num">{{ fmt(chosenSum.results) }}</b></div>
            <div><small>CPA trung bình</small><b class="num" :class="cpaTone(chosenSum.cpa, avg)">{{ chosenCur ? money(chosenSum.cpa, chosenCur) : '–' }}</b></div>
            <div><small>ROAS trung bình</small><b class="num">{{ chosenSum.roas == null || !chosenCur ? '–' : fmtDec(chosenSum.roas) }}</b></div>
          </div>
          <p v-else class="card faint emp">Tick chọn {{ levelName }} ở danh sách, hoặc bấm một nút “Chọn nhanh”.</p>

          <div class="nb">
            <p class="lbl">Ngân sách mới</p>
            <Segmented v-model="act.mode" block :options="modeOpts" size="sm" />
            <input v-model="act.value" class="input val" :class="{ bad: actErr }" inputmode="decimal" :placeholder="act.mode === 'percent' ? 'vd 20 hoặc -20' : act.mode === 'add' ? 'vd 50k hoặc -50k' : 'vd 500k'" aria-label="Giá trị" />
            <small :class="actErr ? 'ferr' : 'faint'">{{ actErr || valueHint }}</small>
          </div>

          <div v-if="chosen.length" class="card tot">
            <span class="faint">Tổng ngân sách/ngày</span>
            <span class="tv"><span class="num faint">{{ fmt(budgetNow) }}</span><template v-if="toRun.length"> → <b class="num">{{ fmt(sumTo) }}</b></template></span>
            <small v-if="skipped" class="faint">Bỏ qua {{ skipped }} mục đã đúng mức hoặc không hợp lệ</small>
          </div>
          <Callout v-if="learningCount" tone="info">{{ learningCount }} mục đang ở giai đoạn học. Đổi ngân sách lúc này có thể khiến Facebook cho học lại.</Callout>
          <Callout v-if="bigCount" tone="warning">{{ bigCount }} mục thay đổi từ gấp đôi hoặc giảm một nửa trở lên. Thay đổi lớn có thể khiến Facebook cho học lại từ đầu.</Callout>
          <Callout v-if="toRun.length > 60" tone="info">Đổi nhiều mục cùng lúc tốn nhiều lượt gọi Facebook. Nếu bị giới hạn, tool dừng lại và cho bạn chạy tiếp phần còn lại sau vài phút.</Callout>

          <div class="go">
            <p v-if="hint" class="faint gh">{{ hint }}</p>
            <Btn variant="primary" size="lg" block :icon="Wallet" :disabled="!toRun.length" :action="start">Đổi ngân sách{{ toRun.length ? ` ${toRun.length} ${levelName}` : '' }}</Btn>
          </div>
        </aside>
      </div>
    </div>

    <!-- Đang chạy / kết quả -->
    <div v-else class="res">
      <div class="bar"><i :style="{ width: (run.done / run.total) * 100 + '%' }" /></div>
      <p class="big num">{{ run.running ? `Đang đổi ${run.done}/${run.total}…` : `Đã đổi ${run.ok}/${run.total} mục` }}</p>
      <Callout v-if="run.rateLimited" tone="warning"><b>Facebook đang giới hạn số lần gọi.</b> Còn {{ run.remaining.length }} mục chưa đổi. Chờ vài phút rồi bấm “Chạy tiếp”.</Callout>
      <Callout v-else-if="!run.running && run.remaining.length" tone="info">Đã dừng. Còn {{ run.remaining.length }} mục chưa đổi.</Callout>
      <Callout v-if="run.fails.length" tone="danger">
        <b>{{ run.fails.length }} mục lỗi</b> (xem chi tiết ở Nhật ký):
        <ul class="fails"><li v-for="x in run.fails.slice(0, 8)" :key="x.name"><b>{{ x.name }}</b>: {{ x.msg }}</li></ul>
      </Callout>
      <p v-if="!run.running" class="faint">Mỗi thay đổi được ghi ở Nhật ký và có thể hoàn tác từng mục.</p>
      <div class="ract">
        <Btn v-if="run.running" :icon="Square" @click="run.stop = true">Dừng</Btn>
        <template v-else>
          <Btn @click="close">Đóng</Btn>
          <Btn v-if="run.remaining.length" variant="primary" :icon="Play" :action="resume">Chạy tiếp {{ run.remaining.length }} mục</Btn>
          <Btn v-else variant="primary" :icon="Check" @click="again">Đổi đợt khác</Btn>
        </template>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.bb { flex: 1; min-height: 0; display: flex; flex-direction: column; }
/* thanh lọc */
.tb { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 0 26px 12px; }
.search { flex: 1; min-width: 200px; max-width: 360px; height: 38px; display: flex; align-items: center; gap: 8px; padding: 0 10px 0 12px; border: 1px solid var(--border-strong); border-radius: 11px; background: var(--surface); color: var(--text-3); }
.search:focus-within { border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); }
.search input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; font: inherit; font-size: 14px; color: var(--text); }
.search button { border: 0; background: none; color: var(--text-3); display: grid; place-items: center; padding: 2px; cursor: pointer; }
.tbtn { height: 38px; display: inline-flex; align-items: center; gap: 7px; padding: 0 12px; border: 1px solid var(--border-strong); border-radius: 11px; background: var(--surface); color: var(--text-2); font: inherit; font-size: 13.5px; font-weight: 600; cursor: pointer; white-space: nowrap; }
.tbtn:hover, .tbtn.on { border-color: var(--accent); color: var(--accent); }
.tbtn em { font-style: normal; font-size: 11px; padding: 0 6px; border-radius: 99px; background: var(--accent); color: #fff; }
.grow { flex: 1; }
.fpop { display: grid; gap: 8px; padding: 14px; }
.ph { margin: 4px 0 0; font-size: 12.5px; font-weight: 650; color: var(--text-2); }
.two { display: flex; gap: 8px; } .mi { flex: 1; display: flex; flex-direction: column; gap: 3px; } .mi small { font-size: 12px; min-height: 15px; }
.fact { display: flex; justify-content: space-between; gap: 10px; margin-top: 6px; padding-top: 10px; border-top: 1px solid var(--border); }
.lnk { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; font-weight: 600; font-size: 13px; cursor: pointer; display: inline-flex; align-items: center; gap: 5px; }
.lnk:disabled { opacity: .5; cursor: default; }
.spin { animation: spin 1s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
.ferr { color: var(--danger); font-size: 13px; margin: 0; } .ferr.pad { padding: 0 26px 10px; }
/* chọn nhanh */
.quick { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 0 26px 12px; font-size: 13px; }
.chip { border: 1px dashed var(--border-strong); border-radius: 99px; padding: 4px 11px; background: var(--surface); color: var(--text-2); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; white-space: nowrap; }
.chip i { font-style: normal; font-weight: 500; color: var(--text-3); }
.chip:hover { border-color: var(--accent); color: var(--accent); }
.chip.on { border-style: solid; border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
.chip .short { display: none; }
/* thân: danh sách | cột phải */
.body { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr) 340px; border-top: 1px solid var(--border); }
.main { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.scroll { flex: 1; min-height: 0; overflow: auto; }
.row { display: grid; grid-template-columns: var(--gt); gap: 14px; align-items: center; padding: 11px 22px; min-width: var(--mw); }
.mets { display: contents; }
.ml { display: none; }
.hd { position: sticky; top: 0; z-index: 1; padding-top: 9px; padding-bottom: 9px; background: var(--surface-2); border-bottom: 1px solid var(--border); font-size: 12.5px; font-weight: 650; color: var(--text-3); }
.row input[type='checkbox'] { accent-color: var(--accent); width: 16px; height: 16px; margin: 0; cursor: pointer; }
.sh { display: inline-flex; align-items: center; gap: 4px; border: 0; background: none; padding: 3px 5px; margin: -3px -5px; border-radius: 6px; font: inherit; color: inherit; cursor: pointer; white-space: nowrap; }
.sh svg { opacity: .45; } .sh:hover { color: var(--text); background: var(--surface-3); } .sh.on { color: var(--accent); } .sh.on svg { opacity: 1; }
.ra { justify-self: end; }
.it { border-bottom: 1px solid var(--border); font-size: 14px; cursor: pointer; transition: background .12s; }
.it:hover { background: var(--surface-2); }
.it.on { background: var(--accent-soft); }
.nm { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.nm b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.dl { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dl span { overflow: hidden; text-overflow: ellipsis; }
.dl i { width: 7px; height: 7px; border-radius: 50%; background: var(--text-3); opacity: .6; flex: none; }
.dl.success i { background: var(--success); opacity: 1; } .dl.info i { background: var(--info); opacity: 1; } .dl.warning i { background: var(--warning); opacity: 1; } .dl.danger i { background: var(--danger); opacity: 1; }
.mc { justify-self: end; text-align: right; white-space: nowrap; font-size: 14px; }
.mc > .num { font-weight: 600; } .mc > .faint { font-weight: 400; }
.cpa { display: inline-flex; align-items: center; gap: 6px; }
.cpa i { width: 7px; height: 7px; border-radius: 50%; flex: none; }
i.good, .cpa i.good { background: var(--success); } .cpa i.mid { background: var(--warning); } .cpa i.bad { background: var(--danger); }
.bud { justify-self: end; display: inline-flex; align-items: baseline; gap: 7px; white-space: nowrap; }
.bud .old, .bud .arr { color: var(--text-3); }
.bud .pc { font-size: 12px; font-weight: 700; color: var(--danger); } .bud .pc.up { color: var(--success); } .bud .pc.bigc { color: var(--warning); }
.badc { font-size: 12px; color: var(--danger); }
.morelnk { display: block; width: 100%; padding: 12px 14px; border: 0; background: var(--surface-2); color: var(--accent); font: inherit; font-size: 13.5px; font-weight: 650; cursor: pointer; }
.morelnk small { color: var(--text-3); font-weight: 500; }
.empty { margin: 0; padding: 40px 22px; text-align: center; font-size: 14px; }
.foot { display: flex; gap: 6px 18px; flex-wrap: wrap; padding: 10px 22px; border-top: 1px solid var(--border); background: var(--surface-2); font-size: 12.5px; }
.foot b { color: var(--text-2); }
.mbar { display: none; }
/* cột phải */
.side { min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 18px 20px; border-left: 1px solid var(--border); background: var(--surface-2); }
.side h4 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 15.5px; }
.cnt { margin-left: auto; font-size: 12.5px; font-weight: 650; padding: 2px 10px; border-radius: 99px; background: var(--surface-3); color: var(--text-2); }
.cnt.on { background: var(--accent-soft); color: var(--accent); }
.card { margin: 0; padding: 12px 14px; border: 1px solid var(--border); border-radius: 14px; background: var(--surface); }
.emp { font-size: 13.5px; }
.kp { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px; }
.kp small { display: block; font-size: 11.5px; font-weight: 600; color: var(--text-3); }
.kp b { font-size: 16px; } .kp b.good { color: var(--success); } .kp b.bad { color: var(--danger); } .kp b.mid { color: var(--warning); }
.nb { display: grid; gap: 8px; }
.lbl { margin: 0; font-size: 13px; font-weight: 650; color: var(--text-2); }
.val { font-weight: 600; } .val.bad { border-color: var(--danger); }
.nb small { font-size: 12.5px; }
.tot { display: grid; gap: 4px; font-size: 13px; }
.tv { font-size: 14px; } .tv b { font-size: 18px; }
.go { margin-top: auto; display: grid; gap: 8px; padding-top: 4px; }
.gh { margin: 0; font-size: 13px; text-align: center; }
.back { display: none; }
/* kết quả */
.res { display: grid; gap: 12px; padding: 8px 26px 24px; overflow: auto; }
.bar { height: 8px; border-radius: 99px; background: var(--surface-3); overflow: hidden; } .bar i { display: block; height: 100%; background: var(--accent-grad); transition: width .3s var(--ease); }
.big { font-size: 20px; font-weight: 700; margin: 0; }
.fails { margin: 6px 0 0; padding-left: 18px; font-size: 13.5px; }
.ract { display: flex; gap: 10px; justify-content: flex-end; }

/* máy tính bảng / cửa sổ hẹp: cột phải hẹp lại */
@media (max-width: 1100px) {
  .body { grid-template-columns: minmax(0, 1fr) 300px; }
  .stt { order: 5; }
}
/* điện thoại: thẻ + thanh dưới; bước nhập ngân sách là một màn riêng */
@media (max-width: 760px) {
  .tb { padding: 0 16px 10px; gap: 8px; }
  .lv { width: 100%; }
  .search { max-width: none; min-width: 0; }
  .tbtn .lb, .cm { display: none; }
  .grow { display: none; }
  .quick { padding: 0 16px 10px; flex-wrap: nowrap; overflow-x: auto; }
  .ql { display: none; }
  .chip .full { display: none; } .chip .short { display: inline; }
  .body { grid-template-columns: 1fr; }
  .side { display: none; border-left: 0; padding: 12px 16px 16px; }
  .st-budget .side { display: flex; }
  .st-budget .main, .st-budget .tb, .st-budget .quick { display: none; }
  .back { display: inline-flex; align-self: flex-start; }
  .scroll { padding: 10px 12px; }
  .hd { display: none; }
  .row { min-width: 0; grid-template-columns: 20px minmax(0, 1fr); gap: 8px 10px; padding: 11px 12px; margin-bottom: 9px; border: 1px solid var(--border); border-radius: 14px; }
  .it:last-of-type { border-bottom: 1px solid var(--border); }
  .it.on { border-color: color-mix(in srgb, var(--accent) 35%, transparent); }
  .mets { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; }
  .mc { justify-self: stretch; text-align: left; padding: 6px 8px; border-radius: 9px; background: var(--surface-2); font-size: 13px; overflow: hidden; text-overflow: ellipsis; }
  .it.on .mc { background: var(--surface); }
  .ml { display: block; font-size: 10.5px; font-weight: 600; color: var(--text-3); }
  .bud { grid-column: 1 / -1; justify-self: stretch; font-size: 13px; }
  .bud .ml { display: inline; margin-right: auto; font-size: 12.5px; font-weight: 500; }
  .foot { display: none; }
  .mbar { display: flex; align-items: center; gap: 12px; padding: 12px 16px calc(12px + env(safe-area-inset-bottom)); border-top: 1px solid var(--border); background: var(--surface); box-shadow: 0 -10px 30px -12px rgba(20, 24, 50, .2); }
  .mt { flex: 1; min-width: 0; display: flex; flex-direction: column; font-size: 12.5px; color: var(--text-2); }
  .mt b { font-size: 15px; color: var(--text); }
  .res { padding: 8px 16px 20px; }
}
</style>
