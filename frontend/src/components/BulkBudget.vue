<script setup>
// Đổi ngân sách hàng loạt, 3 bước như Ads Manager:
//  1. lọc theo điều kiện → 2. tick chọn chiến dịch trong danh sách khớp → 3. nhập ngân sách mới → xác nhận → chạy lần lượt.
// Mỗi mục đi qua đúng API đổi ngân sách thủ công nên được kiểm tra, ghi Nhật ký và hoàn tác được như khi sửa từng camp.
import { ref, reactive, computed, watch } from 'vue'
import { Check, Square, Wallet, Play } from 'lucide-vue-next'
import { state, setObjBudget } from '../stores/app'
import { toast, confirm } from '../stores/ui'
import { fmt } from '../lib/format'
import { MODES, parseMoney, readFilter, readAction, budgetChange } from '../lib/bulkBudget'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'
import FilterPicker from './FilterPicker.vue'

const props = defineProps({ modelValue: Boolean, level: { type: String, default: 'campaign' }, account: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])

const blankFilter = () => ({ level: 'campaign', op: 'lt', x: '', y: '', name: '', onlyRunning: false, account: '' })
const filter = reactive(blankFilter())
const act = reactive({ mode: 'set', value: '' })
const selected = ref([])
const run = ref(null) // { total, done, ok, fails: [{ name, msg }], remaining: [], rateLimited, running, stop }

watch(() => props.modelValue, (open) => {
  if (!open) return
  Object.assign(filter, blankFilter(), { level: props.level, account: props.account })
  Object.assign(act, { mode: 'set', value: '' })
  selected.value = []; run.value = null
})

const levelName = computed(() => (filter.level === 'adset' ? 'nhóm QC' : 'chiến dịch'))
const moneyHint = (v) => { const n = parseMoney(v); return Number.isFinite(n) && v !== '' ? fmt(n) : '' }
const fltOk = computed(() => !Object.keys(readFilter(filter).errors).length)

// ----- Bước 3: ngân sách mới -----
const action = computed(() => readAction(act))
const actReady = computed(() => !Object.keys(action.value.errors).length)
const actErr = computed(() => (act.value !== '' ? action.value.errors.value || '' : ''))
const valueHint = computed(() => (act.mode === 'percent' ? `Số âm để giảm, vd -20${act.value !== '' ? ` → ${act.value}%` : ''}` : act.mode === 'add' ? `Số âm để trừ, vd -50k${moneyHint(act.value) ? ` → ${moneyHint(act.value)}` : ''}` : `Gõ được 500k, 1,5tr hoặc 500.000${moneyHint(act.value) ? ` → ${moneyHint(act.value)}` : ''}`))
// cột "Ngân sách mới" trong danh sách chọn
const change = computed(() => (actReady.value ? (o) => budgetChange(o, action.value.action) : null))

// Sẽ đổi = mục đã chọn có ngân sách mới khác hiện tại
const chosen = computed(() => { const s = new Set(selected.value); return state.objs.filter((o) => s.has(o.id) && o.dailyBudget != null) })
const toRun = computed(() => (actReady.value ? chosen.value.map((o) => ({ o, ch: budgetChange(o, action.value.action) })).filter((r) => r.ch.kind === 'change') : []))
const skipped = computed(() => (actReady.value ? chosen.value.length - toRun.value.length : 0))
const sumFrom = computed(() => toRun.value.reduce((t, r) => t + r.o.dailyBudget, 0))
const sumTo = computed(() => toRun.value.reduce((t, r) => t + r.ch.to, 0))
const bigCount = computed(() => toRun.value.filter((r) => r.ch.to / r.o.dailyBudget >= 2 || r.ch.to / r.o.dailyBudget <= 0.5).length)
const footHint = computed(() => (!fltOk.value ? 'Bước 1: đặt điều kiện lọc'
  : !selected.value.length ? `Bước 2: tick chọn ${levelName.value}`
    : !actReady.value ? 'Bước 3: nhập ngân sách mới'
      : !toRun.value.length ? 'Các mục đã chọn đều không cần đổi' : ''))

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
  if (!list.length) return toast(footHint.value || 'Không có mục nào để đổi', 'error')
  const msg = `Tổng ngân sách/ngày của ${list.length} ${levelName.value}: ${fmt(sumFrom.value)} → ${fmt(sumTo.value)}.`
    + (skipped.value ? ` Bỏ qua ${skipped.value} mục đã đúng mức hoặc không hợp lệ.` : '')
    + (bigCount.value ? ` ${bigCount.value} mục thay đổi từ gấp đôi hoặc giảm một nửa trở lên, Facebook có thể cho học lại.` : '')
    + (state.settings.mock ? '' : ' Thay đổi áp dụng thật lên Facebook ngay.')
  if (!await confirm(`Đổi ngân sách ${list.length} ${levelName.value}?`, msg, { ok: 'Đổi ngân sách' })) return
  await execute(list.map((r) => ({ o: r.o, to: r.ch.to })))
}
const resume = () => execute(run.value.remaining)
const again = () => { run.value = null; selected.value = [] }
const close = () => { if (run.value && run.value.running) run.value.stop = true; emit('update:modelValue', false) }
</script>

<template>
  <Modal :model-value="modelValue" title="Đổi ngân sách hàng loạt" subtitle="Lọc theo điều kiện, chọn chiến dịch, rồi nhập ngân sách mới." width="840px" @update:model-value="(v) => !v && close()">
    <template v-if="!run">
      <!-- Bước 1 + 2: lọc rồi tick chọn -->
      <FilterPicker v-model="selected" v-model:filter="filter" numbered need-budget :change="change" />

      <!-- Bước 3 -->
      <section class="st">
        <h4><span class="no">3</span>Ngân sách mới</h4>
        <Field :error="actErr" :hint="valueHint">
          <div class="inl">
            <Segmented v-model="act.mode" :options="Object.entries(MODES).map(([value, label]) => ({ value, label }))" size="sm" />
            <input v-model="act.value" class="input val" inputmode="decimal" :placeholder="act.mode === 'percent' ? 'vd 20' : 'vd 500k'" aria-label="Giá trị" />
          </div>
        </Field>
        <p v-if="toRun.length" class="tot">
          Tổng ngân sách/ngày của <b>{{ toRun.length }}</b> mục sẽ đổi: <span class="num">{{ fmt(sumFrom) }}</span> → <b class="num">{{ fmt(sumTo) }}</b>
          <span v-if="skipped" class="faint"> · bỏ qua {{ skipped }} mục đã đúng mức hoặc không hợp lệ</span>
        </p>
        <Callout v-if="bigCount" tone="warning">{{ bigCount }} mục thay đổi từ gấp đôi hoặc giảm một nửa trở lên. Thay đổi lớn có thể khiến Facebook cho nhóm quảng cáo học lại từ đầu.</Callout>
        <Callout v-if="toRun.length > 60" tone="info">Đổi nhiều mục cùng lúc tốn nhiều lượt gọi Facebook. Nếu bị giới hạn, tool dừng lại và cho bạn chạy tiếp phần còn lại sau vài phút.</Callout>
      </section>
    </template>

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
    </div>

    <template #footer>
      <template v-if="!run">
        <span v-if="footHint" class="fh faint">{{ footHint }}</span>
        <Btn @click="close">Huỷ</Btn>
        <Btn variant="primary" :icon="Wallet" :disabled="!toRun.length" :action="start">Đổi ngân sách{{ toRun.length ? ` ${toRun.length} ${levelName}` : '' }}</Btn>
      </template>
      <template v-else-if="run.running"><Btn :icon="Square" @click="run.stop = true">Dừng</Btn></template>
      <template v-else>
        <Btn @click="close">Đóng</Btn>
        <Btn v-if="run.remaining.length" variant="primary" :icon="Play" :action="resume">Chạy tiếp {{ run.remaining.length }} mục</Btn>
        <Btn v-else variant="primary" :icon="Check" @click="again">Đổi đợt khác</Btn>
      </template>
    </template>
  </Modal>
</template>

<style scoped>
.st { margin-bottom: 20px; }
.st h4 { display: flex; align-items: center; gap: 10px; font-size: 15.5px; margin: 0 0 10px; letter-spacing: -.01em; }
.no { width: 24px; height: 24px; border-radius: 50%; background: var(--accent-grad); color: #fff; display: grid; place-items: center; font-size: 12.5px; font-weight: 700; flex: none; }
.inl { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.val { width: 150px; }
.tot { font-size: 14px; margin: -4px 0 10px; }
.fh { margin-right: auto; align-self: center; font-size: 13.5px; }

.res { display: grid; gap: 12px; }
.bar { height: 8px; border-radius: 99px; background: var(--surface-3); overflow: hidden; } .bar i { display: block; height: 100%; background: var(--accent-grad); transition: width .3s var(--ease); }
.big { font-size: 20px; font-weight: 700; margin: 0; }
.fails { margin: 6px 0 0; padding-left: 18px; font-size: 13.5px; }
@media (max-width: 620px) {
  .fh { display: none; }
}
</style>
