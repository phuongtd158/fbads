<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { KeyRound, ShieldCheck, Send, SlidersHorizontal, Palette, Lock } from 'lucide-vue-next'
import ConnectionPanel from './settings/ConnectionPanel.vue'
import ModePanel from './settings/ModePanel.vue'
import TelegramPanel from './settings/TelegramPanel.vue'
import GeneralPanel from './settings/GeneralPanel.vue'
import AppearancePanel from './settings/AppearancePanel.vue'
import SecurityPanel from './settings/SecurityPanel.vue'

const route = useRoute()
const router = useRouter()
const tabs = [
  { v: 'connection', label: 'Kết nối Facebook', icon: KeyRound, comp: ConnectionPanel },
  { v: 'mode', label: 'Chế độ hoạt động', icon: ShieldCheck, comp: ModePanel },
  { v: 'telegram', label: 'Telegram', icon: Send, comp: TelegramPanel },
  { v: 'general', label: 'Chung', icon: SlidersHorizontal, comp: GeneralPanel },
  { v: 'appearance', label: 'Giao diện', icon: Palette, comp: AppearancePanel },
  { v: 'security', label: 'Bảo mật', icon: Lock, comp: SecurityPanel },
]
const tab = computed(() => tabs.find((t) => t.v === route.params.tab) || tabs[0])
const go = (v) => router.replace('/settings/' + v)
</script>

<template>
  <div class="layout">
    <nav class="tabs" role="tablist">
      <button v-for="t in tabs" :key="t.v" role="tab" :aria-selected="tab.v === t.v" :class="{ on: tab.v === t.v }" @click="go(t.v)">
        <component :is="t.icon" :size="18" /><span>{{ t.label }}</span>
      </button>
    </nav>
    <div class="panel">
      <component :is="tab.comp" :key="tab.v" class="view" />
    </div>
  </div>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: 230px minmax(0, 1fr); gap: 24px; align-items: start; }
.tabs { position: sticky; top: 20px; display: flex; flex-direction: column; gap: 3px; }
.tabs button { display: flex; align-items: center; gap: 12px; padding: 11px 14px; border: 0; background: none; border-radius: 13px; color: var(--text-2); font-weight: 600; font-size: 14.5px; text-align: left; transition: .18s var(--ease); }
.tabs button:hover { background: var(--surface-3); color: var(--text); }
.tabs button.on { background: var(--surface); color: var(--accent); box-shadow: var(--shadow-sm), 0 0 0 1px var(--border); }
.panel { min-width: 0; }
@media (max-width: 960px) {
  .layout { grid-template-columns: 1fr; gap: 16px; }
  .tabs { position: static; flex-direction: row; overflow-x: auto; padding-bottom: 4px; gap: 6px; margin: 0 -16px; padding: 0 16px 6px; scrollbar-width: none; }
  .tabs::-webkit-scrollbar { display: none; }
  .tabs button { white-space: nowrap; background: var(--surface); border: 1px solid var(--border); padding: 9px 14px; flex: none; }
  .tabs button.on { border-color: var(--accent); background: var(--accent-soft); }
}
</style>
