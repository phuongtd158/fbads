<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Rocket, LayoutDashboard, CalendarClock, Zap, KeyRound, ShieldCheck, Send, Lock, Keyboard, BookText, LifeBuoy, ScrollText, Search, ChevronDown, Lightbulb, Info, AlertTriangle, ArrowRight, ArrowLeft, SearchX, X } from 'lucide-vue-next'
import { TOPICS, topicById, plainText } from '../lib/help'
import { rich } from '../lib/rich'
import EmptyState from '../components/EmptyState.vue'
import OnboardingCard from '../components/OnboardingCard.vue'

const icons = { Rocket, LayoutDashboard, CalendarClock, Zap, KeyRound, ShieldCheck, Send, Lock, Keyboard, BookText, LifeBuoy, ScrollText }
const route = useRoute()
const router = useRouter()
const q = ref('')
const searchEl = ref(null)

const topic = computed(() => topicById(route.params.topic || 'start'))
const index = computed(() => TOPICS.findIndex((t) => t.id === topic.value.id))
const prev = computed(() => TOPICS[index.value - 1])
const next = computed(() => TOPICS[index.value + 1])
watch(() => route.params.topic, () => { if (typeof window !== 'undefined') window.scrollTo({ top: 0 }) })

// Tìm kiếm không phân biệt hoa thường và dấu tiếng Việt
const norm = (s) => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd')
const results = computed(() => {
  const s = norm(q.value.trim())
  if (!s) return []
  const words = s.split(/\s+/)
  return TOPICS.map((t) => {
    const text = plainText(t), n = norm(text)
    if (!words.every((w) => n.includes(w))) return null
    const at = n.indexOf(words[0]), from = Math.max(0, at - 50)
    return { t, snippet: (from > 0 ? '…' : '') + text.slice(from, from + 170) + '…' }
  }).filter(Boolean)
})
function open(id) { q.value = ''; router.push('/help/' + id) }
function focusSearch(e) { if (e.key === '/' && document.activeElement !== searchEl.value && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); searchEl.value && searchEl.value.focus() } }
import { onMounted, onBeforeUnmount } from 'vue'
onMounted(() => window.addEventListener('keydown', focusSearch))
onBeforeUnmount(() => window.removeEventListener('keydown', focusSearch))
</script>

<template>
  <div class="help">
    <div class="sr">
      <Search :size="19" />
      <input ref="searchEl" v-model="q" class="input" placeholder="Tìm trong hướng dẫn… (vd: ngân sách, token, lịch)" />
      <button v-if="q" class="clr" aria-label="Xoá tìm kiếm" @click="q = ''"><X :size="16" /></button>
      <kbd v-else>/</kbd>
    </div>

    <div class="layout">
      <nav class="toc" aria-label="Chủ đề hướng dẫn">
        <button v-for="t in TOPICS" :key="t.id" :class="{ on: !q && topic.id === t.id }" @click="open(t.id)">
          <component :is="icons[t.icon]" :size="18" /><span>{{ t.title }}</span>
        </button>
      </nav>

      <div class="doc-col">
        <!-- Kết quả tìm kiếm -->
        <section v-if="q" class="card doc">
          <h2 class="rt">{{ results.length }} kết quả cho “{{ q }}”</h2>
          <EmptyState v-if="!results.length" :icon="SearchX" title="Không tìm thấy" text="Thử từ khoá khác, ví dụ: token, ngân sách, lịch, rule, Telegram." />
          <button v-for="r in results" :key="r.t.id" class="hit" @click="open(r.t.id)">
            <span class="hi"><component :is="icons[r.t.icon]" :size="18" /></span>
            <span class="hb"><b>{{ r.t.title }}</b><small class="muted">{{ r.snippet }}</small></span>
            <ArrowRight :size="16" class="faint" />
          </button>
        </section>

        <!-- Nội dung chủ đề -->
        <template v-else>
          <OnboardingCard v-if="topic.id === 'start'" embedded />
          <article :key="topic.id" class="card doc view">
            <header>
              <span class="ic"><component :is="icons[topic.icon]" :size="24" /></span>
              <div><h2>{{ topic.title }}</h2><p class="muted">{{ topic.summary }}</p></div>
            </header>

            <template v-for="(b, i) in topic.blocks" :key="i">
              <p v-if="b.t === 'p'" class="p" v-html="rich(b.text)" />

              <div v-else-if="b.t === 'steps'" class="blk">
                <h4 v-if="b.title">{{ b.title }}</h4>
                <ol class="steps"><li v-for="(it, j) in b.items" :key="j"><span v-html="rich(it)" /></li></ol>
              </div>

              <div v-else-if="b.t === 'tips'" class="tips">
                <h4><Lightbulb :size="16" />Mẹo</h4>
                <ul><li v-for="(it, j) in b.items" :key="j" v-html="rich(it)" /></ul>
              </div>

              <div v-else-if="b.t === 'note'" class="note" :class="b.tone">
                <component :is="b.tone === 'warning' ? AlertTriangle : Info" :size="18" /><p v-html="rich(b.text)" />
              </div>

              <div v-else-if="b.t === 'table'" class="tw">
                <table>
                  <thead><tr><th v-for="h in b.head" :key="h">{{ h }}</th></tr></thead>
                  <tbody><tr v-for="(r, j) in b.rows" :key="j"><td v-for="(c, k) in r" :key="k" v-html="rich(c)" /></tr></tbody>
                </table>
              </div>

              <div v-else-if="b.t === 'example'" class="ex">
                <h4>{{ b.title }}</h4>
                <ul><li v-for="(it, j) in b.items" :key="j" v-html="rich(it)" /></ul>
              </div>

              <dl v-else-if="b.t === 'glossary'" class="gl">
                <div v-for="[term, def] in b.items" :key="term"><dt>{{ term }}</dt><dd v-html="rich(def)" /></div>
              </dl>

              <div v-else-if="b.t === 'faq'" class="faq">
                <details v-for="[qq, aa] in b.items" :key="qq">
                  <summary>{{ qq }}<ChevronDown :size="18" /></summary>
                  <p v-html="rich(aa)" />
                </details>
              </div>
            </template>

            <footer class="pn">
              <button v-if="prev" class="pv" @click="open(prev.id)"><ArrowLeft :size="16" /><span><small>Trước</small>{{ prev.title }}</span></button><span v-else />
              <button v-if="next" class="nx" @click="open(next.id)"><span><small>Tiếp</small>{{ next.title }}</span><ArrowRight :size="16" /></button>
            </footer>
          </article>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sr { position: relative; max-width: 620px; margin-bottom: 22px; }
.sr > svg { position: absolute; left: 16px; top: 50%; transform: translateY(-50%); color: var(--text-3); }
.sr .input { padding: 14px 44px 14px 46px; font-size: 16px; border-radius: 16px; box-shadow: var(--shadow-sm); }
.sr kbd, .clr { position: absolute; right: 14px; top: 50%; transform: translateY(-50%); }
.clr { border: 0; background: var(--surface-3); color: var(--text-2); width: 28px; height: 28px; border-radius: 9px; display: grid; place-items: center; }

.layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 24px; align-items: start; }
.toc { position: sticky; top: 20px; display: flex; flex-direction: column; gap: 3px; }
.toc button { display: flex; align-items: center; gap: 12px; padding: 10px 14px; border: 0; background: none; border-radius: 13px; color: var(--text-2); font-weight: 600; font-size: 14.5px; text-align: left; transition: .18s var(--ease); }
.toc button:hover { background: var(--surface-3); color: var(--text); }
.toc button.on { background: var(--surface); color: var(--accent); box-shadow: var(--shadow-sm), 0 0 0 1px var(--border); }

.doc-col { min-width: 0; }
.doc { padding: 30px 34px; }
.doc > header { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; padding-bottom: 20px; border-bottom: 1px solid var(--border); }
.ic { width: 52px; height: 52px; border-radius: 17px; background: var(--accent-grad); color: #fff; display: grid; place-items: center; flex: none; box-shadow: 0 8px 20px -8px var(--accent-ring); }
h2 { font-size: 26px; letter-spacing: -.03em; line-height: 1.2; } .doc > header p { margin-top: 3px; }
.rt { font-size: 18px; margin-bottom: 14px; }
.p { font-size: 15.5px; line-height: 1.75; margin: 0 0 16px; color: var(--text-2); }
.p :deep(strong), li :deep(strong), td :deep(strong), .note :deep(strong) { color: var(--text); font-weight: 650; }
h4 { font-size: 15px; margin: 22px 0 10px; letter-spacing: -.01em; }
.steps { list-style: none; counter-reset: st; margin: 0 0 6px; padding: 0; }
.steps li { counter-increment: st; display: flex; gap: 14px; padding: 12px 0; font-size: 15px; line-height: 1.65; color: var(--text-2); }
.steps li::before { content: counter(st); width: 28px; height: 28px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); font-weight: 700; font-size: 13.5px; display: grid; place-items: center; flex: none; margin-top: -1px; }
.tips { margin: 20px 0; padding: 16px 20px; border-radius: 16px; background: var(--accent-soft); }
.tips h4 { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; color: var(--accent); }
ul { margin: 0; padding-left: 20px; } .tips li, .ex li { font-size: 14.5px; line-height: 1.7; margin: 5px 0; color: var(--text-2); }
.note { display: flex; gap: 12px; padding: 14px 16px; border-radius: 14px; margin: 18px 0; font-size: 14.5px; line-height: 1.65; }
.note svg { flex: none; margin-top: 2px; } .note p { margin: 0; }
.note.info { background: var(--info-soft); color: var(--info); } .note.info p { color: var(--text-2); }
.note.warning { background: var(--warning-soft); color: var(--warning); } .note.warning p { color: var(--text-2); }
.tw { overflow-x: auto; margin: 18px 0; border: 1px solid var(--border); border-radius: 14px; }
table { width: 100%; border-collapse: collapse; min-width: 480px; }
th { text-align: left; font-size: 12.5px; font-weight: 700; color: var(--text-3); background: var(--surface-2); padding: 11px 16px; }
td { padding: 12px 16px; border-top: 1px solid var(--border); font-size: 14.5px; line-height: 1.6; vertical-align: top; color: var(--text-2); }
td:first-child { font-weight: 600; color: var(--text); white-space: nowrap; }
.ex { margin: 18px 0; padding: 16px 20px; border-radius: 16px; background: var(--surface-2); border: 1px dashed var(--border-strong); }
.ex h4 { margin: 0 0 8px; }
.gl { margin: 0; } .gl > div { padding: 14px 0; border-bottom: 1px solid var(--border); display: grid; grid-template-columns: 200px 1fr; gap: 16px; } .gl > div:last-child { border: 0; }
dt { font-weight: 650; } dd { margin: 0; color: var(--text-2); font-size: 14.5px; line-height: 1.65; }
.faq { display: grid; gap: 10px; }
details { border: 1px solid var(--border); border-radius: 14px; background: var(--surface); transition: .2s; }
details[open] { border-color: color-mix(in srgb, var(--accent) 40%, var(--border)); box-shadow: 0 0 0 4px var(--accent-soft); }
summary { list-style: none; cursor: pointer; padding: 15px 18px; font-weight: 620; display: flex; justify-content: space-between; align-items: center; gap: 12px; }
summary::-webkit-details-marker { display: none; }
summary svg { flex: none; color: var(--text-3); transition: transform .25s var(--ease); } details[open] summary svg { transform: rotate(180deg); color: var(--accent); }
details p { margin: 0; padding: 0 18px 16px; color: var(--text-2); font-size: 14.5px; line-height: 1.7; }
.pn { display: flex; justify-content: space-between; gap: 12px; margin-top: 32px; padding-top: 22px; border-top: 1px solid var(--border); }
.pn button { display: flex; align-items: center; gap: 12px; padding: 12px 18px; border: 1px solid var(--border-strong); background: var(--surface); border-radius: 14px; font-weight: 600; text-align: left; transition: .18s var(--ease); }
.pn button:hover { border-color: var(--accent); color: var(--accent); transform: translateY(-1px); }
.pn small { display: block; font-size: 12px; color: var(--text-3); font-weight: 500; } .pn .nx { text-align: right; margin-left: auto; }
.hit { display: flex; align-items: center; gap: 14px; width: 100%; padding: 14px; border: 0; background: none; border-radius: 14px; text-align: left; transition: .15s; }
.hit:hover { background: var(--surface-2); }
.hi { width: 40px; height: 40px; border-radius: 13px; background: var(--accent-soft); color: var(--accent); display: grid; place-items: center; flex: none; }
.hb { flex: 1; min-width: 0; } .hb b { display: block; } .hb small { display: block; font-size: 13.5px; line-height: 1.5; margin-top: 2px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

@media (max-width: 960px) {
  .layout { grid-template-columns: 1fr; gap: 16px; }
  .toc { position: static; flex-direction: row; overflow-x: auto; gap: 6px; margin: 0 -16px; padding: 0 16px 6px; scrollbar-width: none; }
  .toc::-webkit-scrollbar { display: none; }
  .toc button { flex: none; white-space: nowrap; background: var(--surface); border: 1px solid var(--border); padding: 9px 14px; }
  .toc button.on { border-color: var(--accent); background: var(--accent-soft); }
  .doc { padding: 22px 18px; } h2 { font-size: 22px; }
  .gl > div { grid-template-columns: 1fr; gap: 4px; }
  td:first-child { white-space: normal; }
}
</style>
