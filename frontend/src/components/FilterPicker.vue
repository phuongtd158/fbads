<script setup>
// Lọc camp / nhóm QC theo điều kiện rồi tick chọn — dùng chung cho "Đổi ngân sách hàng loạt" và Lịch.
//  v-model           : mảng id đã chọn
//  v-model:filter    : điều kiện lọc { level, op, x, y, name, status, account } (x, y là chữ người gõ, vd "100k";
//                      name: nhiều từ khoá cách nhau bằng dấu phẩy; status: 'all' | 'running' | 'off')
//  auto              : lịch "tự động theo điều kiện" — mọi mục khớp đều được tích sẵn; bỏ tích = loại trừ mục đó
//                      (v-model:exclude = mảng id bị loại trừ). Mục mới khớp về sau vẫn được áp dụng.
//  keepHidden        : giữ lựa chọn của mục đang bị bộ lọc ẩn (lịch: đổi bộ lọc để tìm thêm không làm mất mục đã chọn).
//                      Mặc định bỏ chọn mục không còn khớp (hàng loạt: không đổi mục bạn không nhìn thấy).
//  needBudget        : hành động là đổi ngân sách → ẩn mục dùng ngân sách cấp khác (CBO)
//  change(o)         : ngân sách mới của 1 mục { to, kind } cho cột "Ngân sách mới"; null = không hiện cột
//  numbered          : tiêu đề đánh số "1 Lọc…", "2 Chọn…" (hộp thoại hàng loạt)
//  startOnlySelected : mở ở chế độ "chỉ hiện mục đã chọn" (khi sửa lịch đã có)
//  hideBudget        : ẩn phần lọc theo ngân sách (lịch bật/tắt không cần)
//  tagsOf(id)        : nhãn phụ trên từng dòng, vd các lịch khác đang tác động lên mục đó → [{ text, tone }]
import { ref, computed, watch } from 'vue'
import { ArrowUp, ArrowDown, ArrowUpDown, RefreshCw, X } from 'lucide-vue-next'
import { state, loadObjs } from '../stores/app'
import { fmt } from '../lib/format'
import { CONDS, STATUS_FILTERS, statusOf, parseMoney, readFilter, matchFilter } from '../lib/bulkBudget'
import { DELIVERY, deliveryMap } from '../lib/delivery'
import { accountLabel } from '../lib/accounts'
import Segmented from './Segmented.vue'

const selected = defineModel({ type: Array, default: () => [] })
const exclude = defineModel('exclude', { type: Array, default: () => [] })
const flt = defineModel('filter', { type: Object, required: true })
const props = defineProps({
  auto: Boolean, keepHidden: Boolean, needBudget: Boolean,
  change: { type: Function, default: null }, numbered: Boolean, startOnlySelected: Boolean,
  hideBudget: Boolean, tagsOf: { type: Function, default: null },
})
const statusOpts = Object.entries(STATUS_FILTERS).map(([value, label]) => ({ value, label }))
// Dữ liệu cũ chỉ có onlyRunning → đọc qua statusOf; ghi cả 2 trường
const status = computed({
  get: () => statusOf(flt.value),
  set: (v) => { flt.value.status = v; flt.value.onlyRunning = v === 'running' },
})

const sel = computed(() => new Set(selected.value))
const ex = computed(() => new Set(exclude.value))
const hasAdsets = computed(() => state.objs.some((o) => o.level === 'adset'))
const accounts = computed(() => (state.objsMeta && state.objsMeta.accounts) || [])
const multiAcc = computed(() => accounts.value.length > 1)
const showAcc = computed(() => multiAcc.value && !flt.value.account) // đã lọc 1 tài khoản thì cột này chỉ lặp lại
const levelName = computed(() => (flt.value.level === 'adset' ? 'nhóm QC' : 'chiến dịch'))
// Nhóm QC hay trùng tên ("Nhóm 1") → hiện tên chiến dịch chứa nó
const campName = computed(() => { const m = {}; for (const o of state.objs) if (o.level === 'campaign') m[o.id] = o.name; return m })
const byId = computed(() => { const m = new Map(); for (const o of state.objs) m.set(o.id, o); return m })
// Tải lại danh sách từ Facebook (vd vừa tạo camp mới)
const reloading = ref(false)
async function reload() { reloading.value = true; try { await loadObjs(true) } finally { reloading.value = false } }
const loadedAt = computed(() => (state.objsAt ? state.objsAt.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) : ''))
const typed = (v) => v != null && v !== ''
const moneyHint = (v) => { const n = parseMoney(v); return typed(v) && Number.isFinite(n) ? fmt(n) : '' }

// ----- Lọc -----
const fr = computed(() => readFilter(flt.value))
const fltErr = computed(() => (typed(flt.value.x) || typed(flt.value.y) ? fr.value.errors.x || fr.value.errors.y || '' : ''))
const matched = computed(() => (Object.keys(fr.value.errors).length ? null : matchFilter(state.objs, fr.value.filter)))
const cboHidden = computed(() => (props.needBudget && matched.value ? matched.value.filter((o) => o.dailyBudget == null).length : 0))

// ----- Danh sách -----
const onlySel = ref(props.startOnlySelected)
const showOnlySel = computed(() => onlySel.value && !props.auto)
const dm = computed(() => deliveryMap(state.objs))
const deliveryOf = (o) => DELIVERY[dm.value[o.id]] || DELIVERY.off
const collator = new Intl.Collator('vi', { numeric: true, sensitivity: 'base' })
const sort = ref({ key: '', dir: 'asc' })
const SORT_GET = { name: (o) => o.name, account: (o) => accountLabel(o), budget: (o) => o.dailyBudget ?? -1, spend: (o) => o.metrics.spend }
const rows = computed(() => {
  let list
  if (showOnlySel.value) list = state.objs.filter((o) => sel.value.has(o.id))
  else if (!matched.value) return []
  else list = props.needBudget ? matched.value.filter((o) => o.dailyBudget != null) : [...matched.value]
  const { key, dir } = sort.value
  if (key) list.sort((a, b) => { const r = key === 'name' || key === 'account' ? collator.compare(SORT_GET[key](a), SORT_GET[key](b)) : SORT_GET[key](a) - SORT_GET[key](b); return dir === 'asc' ? r : -r })
  return list.map((o) => ({ o, ch: props.change && o.dailyBudget != null ? props.change(o) : null }))
})
// Chỉ VẼ một phần danh sách (vài nghìn mục thì vẽ hết sẽ giật, nhất là điện thoại). Chọn tất cả / đếm vẫn tính trên toàn bộ rows.
const pageSize = () => (window.matchMedia('(max-width: 620px)').matches ? 40 : 100)
const cap = ref(pageSize())
const shownRows = computed(() => (rows.value.length > cap.value ? rows.value.slice(0, cap.value) : rows.value))
const moreRows = computed(() => Math.min(pageSize(), rows.value.length - shownRows.value.length))
watch([() => flt.value.level, () => flt.value.op, () => flt.value.x, () => flt.value.y, () => flt.value.name, () => status.value, () => flt.value.account, () => sort.value.key, () => sort.value.dir, onlySel], () => { cap.value = pageSize() })
function sortBy(k) {
  const s = sort.value
  sort.value = s.key === k ? { key: k, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key: k, dir: k === 'name' || k === 'account' ? 'asc' : 'desc' }
}
const sortIcon = (k) => (sort.value.key !== k ? ArrowUpDown : sort.value.dir === 'asc' ? ArrowUp : ArrowDown)

// Hàng loạt: bỏ chọn mục không còn hiện (đổi bộ lọc) để không đổi mục bạn không nhìn thấy
watch(rows, (r) => {
  if (props.keepHidden || props.auto) return
  const ids = new Set(r.map((x) => x.o.id))
  if (selected.value.some((id) => !ids.has(id))) selected.value = selected.value.filter((id) => ids.has(id))
})

// ----- Chọn -----
// Ô tích bật = mục được áp dụng: chế độ thường là mục đã chọn; chế độ tự động là mục khớp chưa bị loại trừ
const isOn = (id) => (props.auto ? !ex.value.has(id) : sel.value.has(id))
const chosenVisible = computed(() => rows.value.filter((r) => isOn(r.o.id)).length)
const hiddenChosen = computed(() => selected.value.length - rows.value.filter((r) => sel.value.has(r.o.id)).length)
const allOn = computed(() => rows.value.length > 0 && chosenVisible.value === rows.value.length)
const someOn = computed(() => chosenVisible.value > 0 && !allOn.value)
const flip = (list, set, id) => (set.has(id) ? list.filter((x) => x !== id) : [...list, id])
function toggle(id) {
  if (props.auto) exclude.value = flip(exclude.value, ex.value, id)
  else selected.value = flip(selected.value, sel.value, id)
}
function toggleAll() {
  const ids = rows.value.map((r) => r.o.id), drop = new Set(ids)
  // tất cả đang bật → tắt hết mục đang hiện; ngược lại → bật hết mục đang hiện
  if (props.auto) exclude.value = allOn.value ? [...new Set([...exclude.value, ...ids])] : exclude.value.filter((id) => !drop.has(id))
  else selected.value = allOn.value ? selected.value.filter((id) => !drop.has(id)) : [...new Set([...selected.value, ...ids])]
}
const clearExclude = () => { exclude.value = [] }
// Mục đã chọn nhưng không còn trên tài khoản (đã xoá / đổi tài khoản) → cho bỏ nhanh
const missing = computed(() => (state.objsLoaded ? selected.value.filter((id) => !state.objs.some((o) => o.id === id)) : []))
const clearSel = () => { selected.value = [] }
// Mục đã chọn hiện thành chip phía trên danh sách (bấm X để bỏ), chỉ ở lịch chọn từng mục
const CHIP_MAX = 12
const chips = computed(() => (props.auto || !props.keepHidden ? [] : selected.value.slice(0, CHIP_MAX).map((id) => {
  const o = byId.value.get(id)
  return { id, name: o ? o.name : id, sub: o && o.level === 'adset' ? 'Nhóm QC' : '', missing: !o }
})))
const moreChips = computed(() => Math.max(0, selected.value.length - CHIP_MAX))
const unselect = (id) => { selected.value = selected.value.filter((x) => x !== id) }
const dropMissing = () => { const m = new Set(missing.value); selected.value = selected.value.filter((id) => !m.has(id)) }

const pct = (r) => `${r.ch.to > r.o.dailyBudget ? '+' : ''}${Math.round((r.ch.to / r.o.dailyBudget - 1) * 100)}%`
const big = (r) => r.ch.to / r.o.dailyBudget >= 2 || r.ch.to / r.o.dailyBudget <= 0.5
const rowCls = computed(() => ({ nc: !props.change, wacc: showAcc.value })) // wacc = dòng có cột Tài khoản
</script>

<template>
  <div class="fp">
    <section :class="{ st: numbered }">
      <h4 v-if="numbered"><span class="no">1</span>Lọc theo điều kiện</h4>
      <div class="box">
        <div class="inl">
          <Segmented v-if="hasAdsets" v-model="flt.level" :options="[{ value: 'campaign', label: 'Chiến dịch' }, { value: 'adset', label: 'Nhóm quảng cáo' }]" size="sm" />
          <select v-if="multiAcc" v-model="flt.account" class="input accsel" aria-label="Tài khoản quảng cáo"><option value="">Mọi tài khoản ({{ accounts.length }})</option><option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option></select>
          <span class="grow" />
          <button type="button" class="lnk rl" :disabled="reloading || state.objsLoading" :title="loadedAt ? 'Danh sách tải lúc ' + loadedAt : ''" @click="reload"><RefreshCw :size="13" :class="{ spin: reloading }" />Tải lại<small v-if="loadedAt" class="faint"> ({{ loadedAt }})</small></button>
        </div>
        <div class="inl">
          <span class="lb">Trạng thái</span>
          <Segmented v-model="status" :options="statusOpts" size="sm" />
        </div>
        <div v-if="!hideBudget" class="inl top">
          <span class="lb">Ngân sách/ngày</span>
          <select v-model="flt.op" class="input op" aria-label="Điều kiện ngân sách"><option v-for="(c, k) in CONDS" :key="k" :value="k">{{ c.label }}</option></select>
          <template v-if="flt.op !== 'any'">
            <span class="mi"><input v-model="flt.x" class="input" inputmode="decimal" placeholder="vd 100k" aria-label="Mức ngân sách" /><small class="faint">{{ moneyHint(flt.x) }}</small></span>
            <template v-if="flt.op === 'between'"><span class="lb faint">và</span><span class="mi"><input v-model="flt.y" class="input" inputmode="decimal" placeholder="vd 300k" aria-label="Mức thứ hai" /><small class="faint">{{ moneyHint(flt.y) }}</small></span></template>
          </template>
        </div>
        <p v-if="fltErr" class="ferr">{{ fltErr }}</p>
        <div class="inl">
          <input v-model="flt.name" class="input nmf" placeholder="Tên chứa… (nhiều từ: sale, lead)" title="Nhiều từ khoá cách nhau bằng dấu phẩy, khớp một từ bất kỳ" aria-label="Tên chứa" />
        </div>
      </div>
    </section>

    <section :class="{ st: numbered }">
      <h4 v-if="numbered"><span class="no">2</span>Chọn {{ levelName }}
        <span v-if="rows.length" class="cnt" :class="{ on: chosenVisible }">Đã chọn {{ chosenVisible }}/{{ rows.length }}</span></h4>
      <div v-else class="bar2">
        <template v-if="auto">
          <span class="cnt" :class="{ on: chosenVisible }">{{ matched ? `Áp dụng cho ${chosenVisible}/${rows.length} mục đang khớp` : 'Chưa đủ điều kiện' }}</span>
          <button v-if="exclude.length" type="button" class="lnk" @click="clearExclude">Bỏ loại trừ ({{ exclude.length }})</button>
        </template>
        <span v-else class="cnt" :class="{ on: selected.length }">Đã chọn {{ selected.length }} mục</span>
        <template v-if="!auto && keepHidden">
          <button v-if="hiddenChosen > 0 && !onlySel" type="button" class="lnk" @click="onlySel = true">{{ hiddenChosen }} mục đã chọn đang bị bộ lọc ẩn, xem</button>
          <label class="chk sm"><input v-model="onlySel" type="checkbox" /> Chỉ hiện mục đã chọn</label>
          <button v-if="selected.length" type="button" class="lnk" @click="clearSel">Bỏ chọn hết</button>
        </template>
      </div>

      <div v-if="chips.length" class="chips">
        <span v-for="c in chips" :key="c.id" class="chip" :class="{ miss: c.missing }" :title="c.missing ? 'Không còn trên tài khoản' : c.name"><span class="cn">{{ c.name }}</span><em v-if="c.sub">{{ c.sub }}</em><button type="button" :aria-label="'Bỏ chọn ' + c.name" @click="unselect(c.id)"><X :size="12" /></button></span>
        <button v-if="moreChips" type="button" class="lnk" @click="onlySel = true">+{{ moreChips }} mục khác</button>
      </div>

      <p v-if="!showOnlySel && !matched" class="faint hintp">Nhập mức ngân sách ở trên để xem danh sách, hoặc chọn “Bất kỳ”.</p>
      <template v-else>
        <div v-if="rows.length" class="tbl">
          <div class="r hd" :class="rowCls">
            <input type="checkbox" :checked="allOn" :indeterminate="someOn" aria-label="Chọn tất cả" :title="auto ? 'Áp dụng / loại trừ tất cả mục đang hiện' : 'Chọn / bỏ chọn tất cả mục đang hiện'" @change="toggleAll" />
            <button type="button" class="sh" :class="{ on: sort.key === 'name' }" @click="sortBy('name')">Tên<component :is="sortIcon('name')" :size="12" /></button>
            <button v-if="showAcc" type="button" class="sh h-ac" :class="{ on: sort.key === 'account' }" @click="sortBy('account')">Tài khoản<component :is="sortIcon('account')" :size="12" /></button>
            <button type="button" class="sh ra" :class="{ on: sort.key === 'budget' }" @click="sortBy('budget')">Ngân sách<component :is="sortIcon('budget')" :size="12" /></button>
            <button type="button" class="sh ra sp" :class="{ on: sort.key === 'spend' }" @click="sortBy('spend')">Chi tiêu hôm nay<component :is="sortIcon('spend')" :size="12" /></button>
            <span v-if="change" class="ra">Ngân sách mới</span>
          </div>
          <div class="list">
            <label v-for="r in shownRows" :key="r.o.id" class="r it" :class="[rowCls, { on: isOn(r.o.id), off: auto && !isOn(r.o.id) }]" :title="auto && !isOn(r.o.id) ? 'Đã loại trừ: lịch sẽ bỏ qua mục này' : ''">
              <input type="checkbox" :checked="isOn(r.o.id)" @change="toggle(r.o.id)" />
              <span class="nm"><b :title="r.o.name">{{ r.o.name }}</b>
                <small class="dl" :class="deliveryOf(r.o).tone"><i />{{ deliveryOf(r.o).label }}<em v-if="r.o.level === 'adset'"> · Nhóm QC</em><em v-if="showAcc" class="acc-in"> · {{ accountLabel(r.o) }}</em></small>
                <small v-if="r.o.level === 'adset' && campName[r.o.campaignId]" class="pc" :title="campName[r.o.campaignId]">Chiến dịch: {{ campName[r.o.campaignId] }}</small>
                <span v-if="tagsOf && tagsOf(r.o.id).length" class="otags"><i v-for="t in tagsOf(r.o.id)" :key="t.text" :class="t.tone">{{ t.text }}</i></span></span>
              <span v-if="showAcc" class="ac" :title="'Tài khoản quảng cáo ID ' + r.o.accountId"><b>{{ accountLabel(r.o) }}</b><small v-if="r.o.currency">{{ r.o.currency }}</small></span>
              <span class="num ra" :class="{ faint: r.o.dailyBudget == null }" :title="r.o.dailyBudget == null ? 'Dùng ngân sách chiến dịch (CBO)' : ''">{{ r.o.dailyBudget == null ? 'CBO' : fmt(r.o.dailyBudget) }}</span>
              <span class="num ra faint sp">{{ fmt(r.o.metrics.spend) }}</span>
              <span v-if="change" class="num ra nw">
                <span v-if="!r.ch" class="faint">—</span>
                <template v-else-if="r.ch.kind === 'change'"><b>{{ fmt(r.ch.to) }}</b><small :class="{ bigc: big(r) }">{{ pct(r) }}</small></template>
                <small v-else-if="r.ch.kind === 'same'" class="faint">giữ nguyên</small>
                <small v-else class="badc">không hợp lệ</small>
              </span>
            </label>
            <button v-if="moreRows > 0" type="button" class="morelnk" @click="cap += pageSize()">Hiển thị thêm {{ moreRows }} <small>(đang hiển thị {{ shownRows.length }} / {{ rows.length }})</small></button>
          </div>
        </div>
        <p v-else class="faint hintp">{{ showOnlySel ? 'Chưa chọn mục nào.' : `Không có ${levelName} nào khớp. Thử nới điều kiện.` }}</p>
        <p v-if="cboHidden && !showOnlySel" class="faint note">Ẩn {{ cboHidden }} mục dùng ngân sách cấp khác (CBO), không đổi được ở cấp này.</p>
      </template>
      <p v-if="!auto && missing.length" class="note warnc">{{ missing.length }} mục đã chọn không còn trên tài khoản. <button type="button" class="lnk" @click="dropMissing">Bỏ chọn chúng</button></p>
    </section>
  </div>
</template>

<style scoped>
.fp { display: grid; grid-template-columns: minmax(0, 1fr); gap: 10px; min-width: 0; container-type: inline-size; }
.st { margin-bottom: 10px; }
.st h4 { display: flex; align-items: center; gap: 10px; font-size: 15.5px; margin: 0 0 10px; letter-spacing: -.01em; }
.no { width: 24px; height: 24px; border-radius: 50%; background: var(--accent-grad); color: #fff; display: grid; place-items: center; font-size: 12.5px; font-weight: 700; flex: none; }
.cnt { font-size: 13px; font-weight: 600; padding: 3px 10px; border-radius: 99px; background: var(--surface-3); color: var(--text-2); white-space: nowrap; }
.st h4 .cnt { margin-left: auto; }
.cnt.on { background: var(--accent-soft); color: var(--accent); }
.box { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--border); border-radius: 14px; }
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.inl.top { align-items: flex-start; }
.lb { font-size: 14px; color: var(--text-2); height: 40px; display: inline-flex; align-items: center; }
.op { width: auto; }
.mi { display: inline-flex; flex-direction: column; gap: 3px; } .mi .input { width: 140px; } .mi small { font-size: 12px; min-height: 15px; }
.nmf { flex: 1; min-width: 180px; }
.accsel { width: auto; max-width: 260px; padding: 7px 10px; font-size: 13.5px; }
.chk { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; cursor: pointer; } .chk input { accent-color: var(--accent); width: 16px; height: 16px; }
.chk.sm { font-size: 13px; }
.grow { flex: 1; }
.rl { display: inline-flex; align-items: center; gap: 5px; } .rl small { font-weight: 500; } .rl:disabled { opacity: .6; cursor: default; }
.spin { animation: spin 1s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
.chips { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; margin-bottom: 8px; }
.chip { display: inline-flex; align-items: center; gap: 5px; max-width: 260px; padding: 3px 4px 3px 10px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-size: 13px; font-weight: 600; }
.chip .cn { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chip em { font-style: normal; font-weight: 500; font-size: 11.5px; opacity: .8; white-space: nowrap; }
.chip button { border: 0; background: none; color: inherit; display: grid; place-items: center; padding: 3px; border-radius: 6px; cursor: pointer; flex: none; }
.chip button:hover { background: var(--accent); color: #fff; }
.chip.miss { background: var(--warning-soft); color: var(--warning); }
.pc { font-size: 12px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.otags { display: flex; gap: 4px; flex-wrap: wrap; }
.otags i { font-style: normal; font-size: 11.5px; font-weight: 600; padding: 0 6px; border-radius: 5px; background: var(--surface-3); color: var(--text-2); white-space: nowrap; }
.otags i.success { background: var(--success-soft); color: var(--success); } .otags i.danger { background: var(--danger-soft); color: var(--danger); } .otags i.info { background: var(--info-soft); color: var(--info); }
.ferr { color: var(--danger); font-size: 13px; margin: -4px 0 0; }
.bar2 { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.lnk { border: 0; background: none; padding: 0; color: var(--accent); font-weight: 600; font-size: 13px; cursor: pointer; }
.hintp { font-size: 14px; padding: 12px 14px; border: 1px dashed var(--border-strong); border-radius: 12px; margin: 0; }
.note { font-size: 13px; margin: 8px 2px 0; } .warnc { color: var(--warning); }

/* bảng */
.tbl { border: 1px solid var(--border); border-radius: 14px; overflow-x: auto; overflow-y: hidden; }
.tbl > * { min-width: 500px; } /* hẹp hơn nữa thì bảng cuộn ngang, không ép chồng chữ */
.r { display: grid; grid-template-columns: 20px minmax(0, 1fr) 110px 130px 150px; gap: 12px; align-items: center; padding: 9px 14px; }
.r.nc { grid-template-columns: 20px minmax(0, 1fr) 110px 130px; }
/* cột Tài khoản (khi có nhiều tài khoản và chưa lọc theo 1 tài khoản) */
.r.wacc { grid-template-columns: 20px minmax(0, 1fr) minmax(110px, 150px) 110px 130px 150px; }
.r.wacc.nc { grid-template-columns: 20px minmax(0, 1fr) minmax(110px, 150px) 110px 130px; }
.ac { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ac b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; font-size: 13px; color: var(--text-2); }
.ac small { align-self: flex-start; font-size: 11px; color: var(--text-3); padding: 0 6px; border-radius: 5px; background: var(--surface-3); }
.acc-in { display: none; }
.r input[type='checkbox'] { accent-color: var(--accent); width: 16px; height: 16px; margin: 0; cursor: pointer; }
.hd { background: var(--surface-2); border-bottom: 1px solid var(--border); font-size: 12.5px; font-weight: 650; color: var(--text-3); }
.sh { display: inline-flex; align-items: center; gap: 4px; border: 0; background: none; padding: 3px 5px; margin: -3px -5px; border-radius: 6px; font: inherit; color: inherit; cursor: pointer; white-space: nowrap; }
.sh svg { opacity: .45; } .sh:hover { color: var(--text); background: var(--surface-3); } .sh.on { color: var(--accent); } .sh.on svg { opacity: 1; }
.ra { text-align: right; justify-self: end; }
.list { max-height: 320px; overflow-y: auto; }
.morelnk { display: block; width: 100%; padding: 12px 14px; border: 0; border-top: 1px solid var(--border); background: var(--surface-2); color: var(--accent); font: inherit; font-size: 13.5px; font-weight: 650; cursor: pointer; }
.morelnk small { color: var(--text-3); font-weight: 500; } .morelnk:hover { background: var(--accent-soft); }
.it { border-bottom: 1px solid var(--border); font-size: 14px; transition: background .12s; }
.it { cursor: pointer; }
.it.off .nm b { text-decoration: line-through; color: var(--text-3); }
.it:last-child { border-bottom: 0; }
.it:hover { background: var(--surface-2); }
.it.on { background: var(--accent-soft); }
.nm { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.nm b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.dl { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); }
.dl em { font-style: normal; }
.dl i { width: 7px; height: 7px; border-radius: 50%; background: var(--text-3); opacity: .6; flex: none; }
.dl.success i { background: var(--success); opacity: 1; } .dl.info i { background: var(--info); opacity: 1; } .dl.warning i { background: var(--warning); opacity: 1; } .dl.danger i { background: var(--danger); opacity: 1; }
.nw { display: inline-flex; align-items: baseline; gap: 6px; white-space: nowrap; }
.nw small { font-size: 12px; font-weight: 650; color: var(--text-2); } .nw small.bigc { color: var(--warning); } .badc { color: var(--danger); }
/* Theo bề rộng của chính khung chọn (không phải màn hình): trong hộp thoại hẹp cũng gọn lại */
@container (max-width: 700px) {
  .r { grid-template-columns: 20px minmax(160px, 1fr) minmax(100px, max-content) minmax(150px, max-content); gap: 8px; }
  .r.nc { grid-template-columns: 20px minmax(160px, 1fr) minmax(100px, max-content); }
  .r.wacc { grid-template-columns: 20px minmax(160px, 1fr) minmax(100px, max-content) minmax(150px, max-content); }
  .r.wacc.nc { grid-template-columns: 20px minmax(160px, 1fr) minmax(100px, max-content); }
  .ac, .h-ac { display: none; }
  .acc-in { display: inline; }
  .sp { display: none; }
}
</style>
