<script setup>
// Đổi ngân sách hàng loạt theo điều kiện: chọn điều kiện → xem trước danh sách (bỏ tick được từng mục) → xác nhận → chạy lần lượt.
// Mỗi mục đi qua đúng API đổi ngân sách thủ công nên được kiểm tra, ghi Nhật ký và hoàn tác được như khi sửa từng camp.
import { ref, reactive, computed, watch } from 'vue'
import { Check, Square, Wallet, Play } from 'lucide-vue-next'
import { state, setObjBudget } from '../stores/app'
import { toast, confirm } from '../stores/ui'
import { fmt } from '../lib/format'
import { CONDS, MODES, parseMoney, readForm, planBulk } from '../lib/bulkBudget'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'
import Callout from './Callout.vue'
import Segmented from './Segmented.vue'

const props = defineProps({ modelValue: Boolean, level: { type: String, default: 'campaign' } })
const emit = defineEmits(['update:modelValue'])

const f = reactive({ level: 'campaign', op: 'lt', x: '', y: '', onlyRunning: false, name: '', mode: 'set', value: '' })
const excluded = ref(new Set())
const run = ref(null) // { total, done, ok, fails: [{ name, msg }], remaining: [], rateLimited, running, stop }

watch(() => props.modelValue, (open) => { if (open) { f.level = props.level; excluded.value = new Set(); run.value = null } })
watch(() => [f.level, f.op, f.x, f.y, f.onlyRunning, f.name, f.mode, f.value], () => { excluded.value = new Set() })

const hasAdsets = computed(() => state.objs.some((o) => o.level === 'adset'))
const levelName = computed(() => (f.level === 'adset' ? 'nhóm QC' : 'camp'))
const form = computed(() => readForm(f))
const touched = computed(() => (f.op === 'any' || f.x !== '') && f.value !== '')
const errs = computed(() => (touched.value ? form.value.errors : {}))
const plan = computed(() => (Object.keys(form.value.errors).length ? null : planBulk(state.objs, { filter: form.value.filter, action: form.value.action })))
const chosen = computed(() => (plan.value ? plan.value.items.filter((i) => !excluded.value.has(i.o.id)) : []))
const sum = (list, k) => list.reduce((t, i) => t + i[k], 0)
const big = (i) => i.to / i.from >= 2 || i.to / i.from <= 0.5
const bigCount = computed(() => chosen.value.filter(big).length)

const moneyHint = (v) => { const n = parseMoney(v); return Number.isFinite(n) && v !== '' ? fmt(n) : '' }
const pct = (i) => `${i.to > i.from ? '+' : ''}${Math.round((i.to / i.from - 1) * 100)}%`
function toggle(id) { const s = new Set(excluded.value); s.has(id) ? s.delete(id) : s.add(id); excluded.value = s }
function toggleAll() { excluded.value = excluded.value.size ? new Set() : new Set(plan.value.items.map((i) => i.o.id)) }

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
  const list = chosen.value
  if (!list.length) return toast('Không có mục nào để đổi', 'error')
  const msg = `Tổng ngân sách/ngày của ${list.length} ${levelName.value}: ${fmt(sum(list, 'from'))} → ${fmt(sum(list, 'to'))}.`
    + (bigCount.value ? ` ${bigCount.value} mục thay đổi từ gấp đôi hoặc giảm một nửa trở lên, Facebook có thể cho học lại.` : '')
    + (state.settings.mock ? '' : ' Thay đổi áp dụng thật lên Facebook ngay.')
  if (!await confirm(`Đổi ngân sách ${list.length} ${levelName.value}?`, msg, { ok: 'Đổi ngân sách' })) return
  await execute(list.map((i) => ({ o: i.o, to: i.to })))
}
const resume = () => execute(run.value.remaining)
const close = () => { if (run.value && run.value.running) run.value.stop = true; emit('update:modelValue', false) }
</script>

<template>
  <Modal :model-value="modelValue" title="Đổi ngân sách hàng loạt" subtitle="Chọn điều kiện, xem trước danh sách rồi đổi một lần." width="760px" @update:model-value="(v) => !v && close()">
    <template v-if="!run">
      <Field v-if="hasAdsets" label="Áp dụng cho"><Segmented v-model="f.level" :options="[{ value: 'campaign', label: 'Chiến dịch' }, { value: 'adset', label: 'Nhóm quảng cáo' }]" /></Field>

      <Field label="Ngân sách/ngày hiện tại" :error="errs.x || errs.y">
        <div class="inl">
          <select v-model="f.op" class="input op"><option v-for="(c, k) in CONDS" :key="k" :value="k">{{ c.label }}</option></select>
          <template v-if="f.op !== 'any'">
            <span class="mi"><input v-model="f.x" class="input" inputmode="decimal" placeholder="vd 100k" aria-label="Mức ngân sách" /><small class="faint">{{ moneyHint(f.x) }}</small></span>
            <template v-if="f.op === 'between'"><span class="faint">và</span><span class="mi"><input v-model="f.y" class="input" inputmode="decimal" placeholder="vd 300k" aria-label="Mức thứ hai" /><small class="faint">{{ moneyHint(f.y) }}</small></span></template>
          </template>
        </div>
      </Field>

      <div class="two">
        <Field label="Tên chứa (không bắt buộc)"><input v-model="f.name" class="input" placeholder="vd Phương" /></Field>
        <Field label="Phân phối"><label class="chk"><input v-model="f.onlyRunning" type="checkbox" /> Chỉ mục đang chạy</label></Field>
      </div>

      <Field label="Đổi thành" :error="errs.value" :hint="f.mode === 'percent' ? 'Số âm để giảm, vd -20' : f.mode === 'add' ? 'Số âm để trừ, vd -50k' : 'Có thể gõ 500k, 1,5tr hoặc 500.000'">
        <div class="inl">
          <Segmented v-model="f.mode" :options="Object.entries(MODES).map(([value, label]) => ({ value, label }))" size="sm" />
          <span class="mi"><input v-model="f.value" class="input" inputmode="decimal" :placeholder="f.mode === 'percent' ? 'vd 20' : 'vd 500k'" aria-label="Giá trị" />
            <small class="faint">{{ f.mode === 'percent' ? (f.value !== '' ? `${f.value}%` : '') : moneyHint(f.value) }}</small></span>
        </div>
      </Field>

      <div v-if="plan" class="pv">
        <div class="pvh">
          <b>{{ plan.items.length }} {{ levelName }} khớp điều kiện</b>
          <span v-if="plan.skipped.noBudget || plan.skipped.same || plan.skipped.invalid" class="faint">· bỏ qua
            <template v-if="plan.skipped.noBudget">{{ plan.skipped.noBudget }} mục dùng ngân sách cấp khác (CBO) </template>
            <template v-if="plan.skipped.same">{{ plan.skipped.same }} mục đã đúng mức </template>
            <template v-if="plan.skipped.invalid">{{ plan.skipped.invalid }} mục sẽ về 0 hoặc quá lớn</template>
          </span>
          <button v-if="plan.items.length" type="button" class="lnk" @click="toggleAll">{{ excluded.size ? 'Chọn tất cả' : 'Bỏ chọn tất cả' }}</button>
        </div>
        <div v-if="plan.items.length" class="list">
          <label v-for="i in plan.items" :key="i.o.id" class="it" :class="{ offi: excluded.has(i.o.id) }">
            <input type="checkbox" :checked="!excluded.has(i.o.id)" @change="toggle(i.o.id)" />
            <span class="nm" :title="i.o.name">{{ i.o.name }}</span>
            <span class="num ch">{{ fmt(i.from) }} → <b>{{ fmt(i.to) }}</b></span>
            <span class="num dp" :class="{ bigc: big(i) }">{{ pct(i) }}</span>
          </label>
        </div>
        <p v-else class="faint none">Không có {{ levelName }} nào khớp. Thử nới điều kiện.</p>
        <p v-if="chosen.length" class="tot">Tổng ngân sách/ngày: <span class="num">{{ fmt(sum(chosen, 'from')) }}</span> → <b class="num">{{ fmt(sum(chosen, 'to')) }}</b></p>
      </div>
      <Callout v-if="bigCount" tone="warning">{{ bigCount }} mục thay đổi từ gấp đôi hoặc giảm một nửa trở lên. Thay đổi lớn có thể khiến Facebook cho nhóm quảng cáo học lại từ đầu.</Callout>
      <Callout v-if="chosen.length > 60" tone="info">Đổi nhiều mục cùng lúc tốn nhiều lượt gọi Facebook. Nếu bị giới hạn, tool dừng lại và cho bạn chạy tiếp phần còn lại sau vài phút.</Callout>
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
        <Btn @click="close">Huỷ</Btn>
        <Btn variant="primary" :icon="Wallet" :disabled="!chosen.length" :action="start">Đổi ngân sách {{ chosen.length || '' }} {{ levelName }}</Btn>
      </template>
      <template v-else-if="run.running"><Btn :icon="Square" @click="run.stop = true">Dừng</Btn></template>
      <template v-else>
        <Btn @click="close">Đóng</Btn>
        <Btn v-if="run.remaining.length" variant="primary" :icon="Play" :action="resume">Chạy tiếp {{ run.remaining.length }} mục</Btn>
        <Btn v-else variant="primary" :icon="Check" @click="run = null">Đổi đợt khác</Btn>
      </template>
    </template>
  </Modal>
</template>

<style scoped>
.inl { display: flex; gap: 10px; align-items: flex-start; flex-wrap: wrap; }
.op { width: auto; }
.mi { display: inline-flex; flex-direction: column; gap: 3px; } .mi .input { width: 150px; } .mi small { font-size: 12px; min-height: 15px; }
.two { display: grid; grid-template-columns: 1fr auto; gap: 14px; align-items: start; }
.chk { display: inline-flex; align-items: center; gap: 8px; height: 40px; font-size: 14.5px; cursor: pointer; } .chk input { accent-color: var(--accent); width: 17px; height: 17px; }
.pv { border: 1px solid var(--border); border-radius: 14px; overflow: hidden; margin: 6px 0 12px; }
.pvh { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 11px 14px; background: var(--surface-2); border-bottom: 1px solid var(--border); font-size: 14px; }
.lnk { margin-left: auto; border: 0; background: none; color: var(--accent); font-weight: 600; font-size: 13.5px; cursor: pointer; }
.list { max-height: 300px; overflow-y: auto; }
.it { display: grid; grid-template-columns: auto minmax(0, 1fr) auto 56px; gap: 10px; align-items: center; padding: 9px 14px; border-bottom: 1px solid var(--border); font-size: 14px; cursor: pointer; }
.it:last-child { border-bottom: 0; } .it:hover { background: var(--surface-2); }
.it input { accent-color: var(--accent); width: 16px; height: 16px; }
.it.offi { opacity: .45; }
.nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ch { color: var(--text-2); white-space: nowrap; } .ch b { color: var(--text); }
.dp { text-align: right; font-weight: 650; color: var(--text-2); font-size: 13px; } .dp.bigc { color: var(--warning); }
.none { padding: 14px; font-size: 14px; }
.tot { padding: 10px 14px; margin: 0; border-top: 1px solid var(--border); font-size: 14px; background: var(--surface-2); }
.res { display: grid; gap: 12px; }
.bar { height: 8px; border-radius: 99px; background: var(--surface-3); overflow: hidden; } .bar i { display: block; height: 100%; background: var(--accent-grad); transition: width .3s var(--ease); }
.big { font-size: 20px; font-weight: 700; margin: 0; }
.fails { margin: 6px 0 0; padding-left: 18px; font-size: 13.5px; }
@media (max-width: 560px) { .two { grid-template-columns: 1fr; } .it { grid-template-columns: auto minmax(0, 1fr) auto; } .dp { display: none; } }
</style>
