<script setup>
defineProps({ icon: [Object, Function], title: String, text: String, tone: { type: String, default: 'accent' } })
</script>

<template>
  <div class="empty">
    <div class="ico" :class="tone"><component :is="icon" :size="26" /></div>
    <h3>{{ title }}</h3>
    <p v-if="text" class="muted">{{ text }}</p>
    <div v-if="$slots.default" class="act"><slot /></div>
  </div>
</template>

<style scoped>
.empty { text-align: center; padding: 56px 24px; }
.ico { width: 64px; height: 64px; border-radius: 22px; display: grid; place-items: center; margin: 0 auto 16px; }
.accent { background: var(--accent-soft); color: var(--accent); }
.danger { background: var(--danger-soft); color: var(--danger); }
.warning { background: var(--warning-soft); color: var(--warning); }
h3 { font-size: 18px; letter-spacing: -.02em; margin-bottom: 6px; }
p { max-width: 440px; margin: 0 auto; }
.act { margin-top: 20px; display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; }
</style>
