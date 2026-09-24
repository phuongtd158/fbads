<script setup>
import { ref, onMounted } from 'vue'
import { Plus, Play, Pencil, Trash2, Zap, Timer, Clock3 } from 'lucide-vue-next'
import { state, loadState, ensureObjs } from '../stores/app'
import { toast, toastError, confirm } from '../stores/ui'
import { api } from '../lib/api'
import { fmt } from '../lib/format'
import { METRICS, RULE_PRESETS } from '../lib/constants'
import Btn from '../components/Btn.vue'
import Switch from '../components/Switch.vue'
import EmptyState from '../components/EmptyState.vue'
import RuleEditor from '../components/RuleEditor.vue'

const editor = ref(false)
const editing = ref(null)
const busy = ref({})
onMounted(() => ensureObjs())

const nameOf = (id) => (state.objs.find((o) => o.id === id) || { name: id }).name
const val = (r) => (r.metric === 'roas' ? r.value : fmt(r.value))
const actTone = (r) => (r.action === 'pause' ? 'bad' : r.action === 'increase' ? 'ok' : 'acc')
const actText = (r) => (r.action === 'pause' ? 'tắt camp' : `${r.action === 'increase' ? 'tăng' : 'giảm'} ${r.pct}% ngân sách`)

function open(item) { editing.value = item; editor.value = true }
async function setEnabled(r, on) {
  busy.value[r.id] = true
  try { await api('rules', 'POST', { ...r, enabled: on }); r.enabled = on; toast(on ? 'Đã bật rule' : 'Đã tạm dừng rule') } catch (e) { toastError(e) } finally { busy.value[r.id] = false }
}
async function remove(r) {
  if (!await confirm('Xoá rule này?', `“${r.name}” sẽ bị xoá vĩnh viễn.`, { ok: 'Xoá', danger: true })) return
  await api(`rules/${r.id}`, 'DELETE'); toast('Đã xoá rule'); await loadState()
}
const runNow = async () => { await api('rules/run', 'POST'); toast('Đã kiểm tra rule — xem Nhật ký') }
</script>

<template>
  <div>
    <Teleport to="#page-actions" defer>
      <Btn :icon="Play" :action="runNow">Kiểm tra ngay</Btn>
      <Btn variant="primary" :icon="Plus" @click="open(null)">Thêm rule</Btn>
    </Teleport>

    <div class="info card">
      <Timer :size="18" /><p>Tool kiểm tra rule mỗi <b>{{ state.settings.ruleIntervalMin }} phút</b> dựa trên số liệu <b>hôm nay</b>. Luôn đặt “chi tiêu tối thiểu” để không quyết định khi dữ liệu còn ít, và đặt trần ngân sách khi dùng rule tăng. <RouterLink to="/help/rules">Xem hướng dẫn về rule →</RouterLink></p>
    </div>

    <div class="presets">
      <span class="faint">Tạo nhanh</span>
      <button v-for="p in RULE_PRESETS" :key="p.name" class="chip" @click="open({ ...p.d })"><Plus :size="14" />{{ p.name }}</button>
    </div>

    <div v-if="state.rules.length" class="grid stagger">
      <article v-for="r in state.rules" :key="r.id" class="card it" :class="{ off: !r.enabled }">
        <div class="hd"><h4>{{ r.name }}</h4><Switch :model-value="r.enabled" :loading="busy[r.id]" :label="'Bật/tắt rule ' + r.name" @update:model-value="(v) => setEnabled(r, v)" /></div>
        <p class="sentence">Nếu <b>{{ METRICS[r.metric] }}</b> {{ r.op === '>' ? 'lớn hơn' : 'nhỏ hơn' }} <b>{{ val(r) }}</b><template v-if="r.minSpend"> (đã chi ≥ {{ fmt(r.minSpend) }})</template>
          thì <b :class="actTone(r)">{{ actText(r) }}</b><template v-if="r.maxBudget">, tối đa {{ fmt(r.maxBudget) }}</template><template v-if="r.minBudget">, tối thiểu {{ fmt(r.minBudget) }}</template>.</p>
        <div class="tags">
          <span v-if="r.allActive" class="tag">Tất cả camp đang chạy</span>
          <template v-else><span v-for="id in r.targets.slice(0, 3)" :key="id" class="tag">{{ nameOf(id) }}</span><span v-if="r.targets.length > 3" class="tag">+{{ r.targets.length - 3 }}</span></template>
          <span v-if="r.from && r.to" class="tag"><Clock3 :size="12" /> {{ r.from }}–{{ r.to }}</span>
          <span v-if="r.cooldownHours" class="tag">Nghỉ {{ r.cooldownHours }}h</span>
        </div>
        <div class="acts"><Btn size="sm" :icon="Pencil" @click="open(r)">Sửa</Btn><span class="grow" /><Btn size="sm" variant="ghost danger" :icon="Trash2" :action="() => remove(r)" aria-label="Xoá" /></div>
      </article>
    </div>
    <section v-else class="card"><EmptyState :icon="Zap" title="Chưa có rule nào" text="Rule giúp tool tự tắt camp lỗ và tăng ngân sách camp tốt khi bạn không online. Chọn một mẫu ở trên để bắt đầu."><Btn variant="primary" :icon="Plus" @click="open(null)">Thêm rule đầu tiên</Btn></EmptyState></section>

    <RuleEditor v-model="editor" :item="editing" @saved="loadState" />
  </div>
</template>

<style scoped>
.info { display: flex; gap: 12px; align-items: flex-start; padding: 14px 18px; margin-bottom: 16px; color: var(--text-2); font-size: 14px; }
.info svg { color: var(--accent); flex: none; margin-top: 2px; }
.info b { color: var(--text); }
.presets { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 18px; }
.presets > span { font-size: 13px; font-weight: 600; margin-right: 4px; }
.chip { display: inline-flex; align-items: center; gap: 6px; border: 1px dashed var(--border-strong); background: transparent; padding: 7px 14px; border-radius: 99px; font-weight: 600; font-size: 13.5px; color: var(--text-2); transition: .18s var(--ease); }
.chip:hover { border-style: solid; border-color: var(--accent); color: var(--accent); background: var(--accent-soft); transform: translateY(-1px); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; }
.it { padding: 20px; display: flex; flex-direction: column; gap: 14px; transition: transform .2s var(--ease), box-shadow .2s, opacity .2s; }
.it:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.it.off { opacity: .6; }
.hd { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
h4 { font-size: 16px; font-weight: 650; letter-spacing: -.01em; }
.sentence { font-size: 15.5px; line-height: 2; color: var(--text-2); }
.sentence b { color: var(--text); background: var(--surface-3); padding: 2px 9px; border-radius: 8px; font-weight: 650; }
.sentence b.bad { background: var(--danger-soft); color: var(--danger); }
.sentence b.ok { background: var(--success-soft); color: var(--success); }
.sentence b.acc { background: var(--accent-soft); color: var(--accent); }
.tags { display: flex; gap: 6px; flex-wrap: wrap; }
.tag { display: inline-flex; align-items: center; gap: 5px; background: var(--surface-3); color: var(--text-2); padding: 2px 10px; border-radius: 8px; font-size: 13px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.acts { display: flex; gap: 8px; margin-top: auto; padding-top: 14px; border-top: 1px solid var(--border); }
.grow { flex: 1; }
</style>
