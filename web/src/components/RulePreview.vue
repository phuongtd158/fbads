<script setup>
import { computed, ref, watch } from 'vue'
import { Loader2, CheckCircle2, MinusCircle, SkipForward, CircleAlert, Eye, Bell } from 'lucide-vue-next'
import { fmt, fmtDec } from '../lib/format'
import { METRICS, METRIC_SHORT, RANGE_LABEL } from '../lib/constants'
import { evaluated, matchWord } from '../lib/ruleText'
import Badge from './Badge.vue'
import Callout from './Callout.vue'

// data: kết quả /api/rules/preview; rule: rule đang xem (để định dạng số liệu)
const props = defineProps({ data: Object, rule: Object, loading: Boolean, error: String })

const order = { match: 0, error: 1, skip: 2, nochange: 3, nomatch: 4 }
const items = computed(() => [...((props.data && props.data.items) || [])].sort((a, b) => order[a.status] - order[b.status]))
// Mục không khớp thường rất nhiều: ẩn đi, chỉ hiện khi bạn bấm xem
const showMiss = ref(false)
watch(() => props.data, () => { showMiss.value = false })
const missCount = computed(() => items.value.filter((i) => i.status === 'nomatch').length)
const shown = computed(() => (showMiss.value ? items.value : items.value.filter((i) => i.status !== 'nomatch')))
const val = (i) => (i.inf ? '∞' : i.value == null ? '–' : props.rule && props.rule.metric === 'roas' ? fmtDec(i.value) : fmt(i.value))
// Từng điều kiện với giá trị thực tế: "CPA 250.000 > 150.000 ✓ VÀ ROAS 2,00 < 1,50 ✗" (? = tài khoản chưa đặt mục tiêu)
const line = (i) => {
  if (!i.conds || !i.conds.length) return `${METRICS[props.rule.metric]} = ${val(i)}`
  return i.conds.map((c) => {
    const e = evaluated(c)
    return `${METRIC_SHORT[c.metric]} ${e.actual} ${e.op} ${e.threshold}${e.target ? ` (${e.target})` : ''} ${e.unknown ? '?' : e.hit ? '✓' : '✗'}`
  }).join(` ${matchWord(props.rule && props.rule.match)} `)
}
const meta = {
  match: { tone: 'success', label: 'Sẽ tác động', icon: CheckCircle2 },
  error: { tone: 'danger', label: 'Sẽ lỗi', icon: CircleAlert },
  skip: { tone: 'warning', label: 'Bỏ qua', icon: SkipForward },
  nochange: { tone: 'neutral', label: 'Không đổi', icon: MinusCircle },
  nomatch: { tone: 'neutral', label: 'Không khớp', icon: MinusCircle },
}
// Mục khớp điều kiện nhưng bị chặn (đang nghỉ, đang học, chạm giới hạn ngày…)
const blocked = computed(() => items.value.filter((i) => i.status === 'skip' && i.hit).length)
const modeNote = computed(() => {
  const d = props.data
  if (!d) return ''
  return d.mode === 'dry' ? 'Đang ở chế độ Chạy thử: khi chạy thật sự tool chỉ ghi Nhật ký, chưa đổi camp.' : d.mode === 'mock' ? 'Đang dùng dữ liệu giả (Dùng thử).' : ''
})
</script>

<template>
  <section class="pv">
    <header><Eye :size="16" /><b>Xem trước</b><span v-if="rule" class="faint">— số liệu {{ RANGE_LABEL[(data && data.range) || rule.range || 'today'] }}</span></header>

    <div v-if="loading" class="ld"><Loader2 class="spin" :size="18" /> Đang kiểm tra với dữ liệu hiện tại…</div>
    <Callout v-else-if="error" tone="danger">{{ error }}</Callout>
    <template v-else-if="data">
      <p class="sum">
        <template v-if="!data.counts.total">Không có {{ rule && rule.level === 'adset' ? 'nhóm QC' : 'camp' }} nào <b>đang chạy</b> để kiểm tra (rule chỉ xét mục đang chạy).</template>
        <template v-else-if="data.counts.match"><b>{{ data.counts.match }}</b> trên {{ data.counts.total }} mục sẽ bị tác động nếu rule chạy ngay bây giờ.</template>
        <template v-else-if="blocked">Có <b>{{ blocked }}</b> mục khớp điều kiện nhưng đang bị bỏ qua — xem lý do bên dưới.</template>
        <template v-else>Hiện <b>không có mục nào</b> khớp rule ({{ data.counts.total }} mục được kiểm tra).</template>
      </p>
      <p v-if="modeNote" class="note faint">{{ modeNote }}</p>
      <Callout v-for="w in data.warnings || []" :key="w" tone="warning">{{ w }}</Callout>
      <ul>
        <li v-for="i in shown" :key="i.id" :class="i.status">
          <component :is="meta[i.status].icon" :size="17" class="ic" />
          <div class="tx">
            <b>{{ i.name }}<Badge v-if="i.learning" tone="info">Đang học</Badge></b>
            <small v-if="i.status === 'match'" class="muted"><Bell v-if="i.result && i.result.notify" :size="12" /> <b>{{ line(i) }}</b> · {{ i.result ? i.result.detail : '' }}</small>
            <small v-else-if="i.reason" class="muted"><template v-if="i.hit || i.code === 'notarget'"><b>{{ line(i) }}</b> · </template>{{ i.reason }}</small>
            <small v-else class="faint">{{ line(i) }} · chi tiêu {{ fmt(i.spend) }}</small>
          </div>
          <Badge :tone="meta[i.status].tone">{{ meta[i.status].label }}</Badge>
        </li>
      </ul>
      <button v-if="missCount" type="button" class="more" @click="showMiss = !showMiss">{{ showMiss ? 'Ẩn' : 'Xem' }} {{ missCount }} mục không khớp</button>
    </template>
  </section>
</template>

<style scoped>
.pv { border: 1px solid var(--border-strong); border-radius: var(--r-md); background: var(--surface-2); padding: 14px 16px; margin: 4px 0 12px; }
header { display: flex; align-items: center; gap: 8px; font-size: 13.5px; margin-bottom: 8px; color: var(--accent); } header b { color: var(--text); }
.ld { display: flex; gap: 10px; align-items: center; color: var(--text-2); font-size: 14px; padding: 6px 0; }
.sum { margin: 0 0 4px; font-size: 14.5px; } .note { margin: 0 0 10px; font-size: 13px; }
.more { margin-top: 8px; border: 0; background: none; padding: 4px 0; font: inherit; font-size: 13px; font-weight: 600; color: var(--accent); cursor: pointer; }
.more:hover { text-decoration: underline; }
ul:empty { display: none; }
ul { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 6px; max-height: 260px; overflow: auto; }
li { display: flex; gap: 10px; align-items: center; padding: 9px 12px; border-radius: 12px; background: var(--surface); border: 1px solid var(--border); }
li.match { border-color: color-mix(in srgb, var(--success) 35%, var(--border)); }
.ic { flex: none; color: var(--text-3); } li.match .ic { color: var(--success); } li.skip .ic { color: var(--warning); } li.error .ic { color: var(--danger); }
.tx { flex: 1; min-width: 0; } .tx b { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 620; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tx small { display: block; font-size: 12.5px; margin-top: 1px; line-height: 1.4; } .tx small b { display: inline; font-size: inherit; }
</style>
