<script setup>
import { Check, ArrowRight, X, BookOpen, Sparkles } from 'lucide-vue-next'
import { steps, doneCount, current, setHidden } from '../stores/onboarding'
import Btn from './Btn.vue'

defineProps({ embedded: Boolean }) // embedded: dùng trong trang Hướng dẫn (không có nút ẩn)
</script>

<template>
  <section class="card ob">
    <header>
      <span class="ic"><Sparkles :size="20" /></span>
      <div class="t"><h3>Bắt đầu nhanh</h3><p class="muted">Hoàn thành {{ doneCount }}/{{ steps.length }} bước để tool làm việc thay bạn.</p></div>
      <RouterLink v-if="!embedded" to="/help/start"><Btn size="sm" :icon="BookOpen">Xem hướng dẫn</Btn></RouterLink>
      <button v-if="!embedded" class="x" title="Ẩn (xem lại ở trang Hướng dẫn)" aria-label="Ẩn" @click="setHidden(true)"><X :size="18" /></button>
    </header>
    <div class="bar"><i :style="{ width: (doneCount / steps.length) * 100 + '%' }" /></div>
    <ol>
      <li v-for="s in steps" :key="s.id" :class="{ done: s.done, now: current && current.id === s.id }">
        <span class="dot"><Check v-if="s.done" :size="14" /></span>
        <div class="tx"><b>{{ s.title }}<em v-if="s.optional">tuỳ chọn</em></b><small class="muted">{{ s.desc }}</small></div>
        <RouterLink v-if="!s.done" :to="s.to" class="go">{{ s.cta }} <ArrowRight :size="14" /></RouterLink>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.ob { padding: 20px 22px 8px; margin-bottom: 20px; position: relative; overflow: hidden; }
.ob::before { content: ''; position: absolute; inset: 0 0 auto 0; height: 3px; background: var(--accent-grad); }
header { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.ic { width: 42px; height: 42px; border-radius: 14px; background: var(--accent-soft); color: var(--accent); display: grid; place-items: center; flex: none; }
.t { flex: 1; min-width: 200px; } h3 { font-size: 17px; letter-spacing: -.02em; } .t p { font-size: 14px; }
.x { width: 34px; height: 34px; border: 0; border-radius: 10px; background: none; color: var(--text-3); display: grid; place-items: center; }
.x:hover { background: var(--surface-3); color: var(--text); }
.bar { height: 6px; border-radius: 99px; background: var(--surface-3); margin: 16px 0 6px; overflow: hidden; }
.bar i { display: block; height: 100%; background: var(--accent-grad); border-radius: 99px; transition: width .6s var(--ease); }
ol { list-style: none; margin: 0; padding: 0; }
li { display: flex; align-items: center; gap: 14px; padding: 13px 4px; border-bottom: 1px solid var(--border); }
li:last-child { border-bottom: 0; }
.dot { width: 24px; height: 24px; border-radius: 50%; border: 2px solid var(--border-strong); display: grid; place-items: center; flex: none; color: #fff; transition: .3s var(--ease); }
li.done .dot { background: var(--success); border-color: var(--success); }
li.now .dot { border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); }
.tx { flex: 1; min-width: 0; } .tx b { display: block; font-size: 15px; font-weight: 620; } .tx small { font-size: 13.5px; }
li.done .tx b { color: var(--text-3); text-decoration: line-through; text-decoration-color: var(--border-strong); }
em { font-style: normal; font-size: 11.5px; font-weight: 600; margin-left: 8px; padding: 1px 8px; border-radius: 99px; background: var(--surface-3); color: var(--text-3); }
.go { display: inline-flex; align-items: center; gap: 5px; font-weight: 650; font-size: 13.5px; color: var(--accent); text-decoration: none; padding: 6px 12px; border-radius: 10px; background: var(--accent-soft); white-space: nowrap; transition: .15s; }
.go:hover { background: var(--accent); color: #fff; }
li:not(.now) .go { background: none; color: var(--text-2); } li:not(.now) .go:hover { background: var(--surface-3); color: var(--text); }
</style>
