import { reactive, ref, computed, watch } from 'vue'
import { ACCENTS } from '../lib/constants'

// ---------- Toast ----------
export const toasts = reactive([])
let toastId = 0
export function toast(message, kind = 'success') {
  const id = ++toastId
  toasts.push({ id, message, kind })
  setTimeout(() => { const i = toasts.findIndex((t) => t.id === id); if (i >= 0) toasts.splice(i, 1) }, kind === 'error' ? 5500 : 3000)
}
export const toastError = (e) => { if (!e || !e.silent) toast((e && e.message) || String(e), 'error') }

// ---------- Confirm ----------
export const confirmState = reactive({ open: false, title: '', message: '', ok: 'Xác nhận', danger: false, resolve: null })
export function confirm(title, message, { ok = 'Xác nhận', danger = false } = {}) {
  return new Promise((resolve) => Object.assign(confirmState, { open: true, title, message, ok, danger, resolve }))
}
export function settleConfirm(v) { confirmState.open = false; if (confirmState.resolve) confirmState.resolve(v) }

// ---------- Command palette ----------
export const palette = ref(false)

// ---------- Theme (sáng / tối / theo hệ thống) + màu nhấn ----------
const read = (k, d) => { try { return localStorage.getItem(k) || d } catch { return d } }
const write = (k, v) => { try { localStorage.setItem(k, v) } catch { /* private mode */ } }
const media = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(media.matches)
media.addEventListener('change', (e) => { systemDark.value = e.matches })

export const themeMode = ref(read('theme', 'system'))
export const accent = ref(read('accent', 'indigo'))
export const resolvedTheme = computed(() => (themeMode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : themeMode.value))

watch([resolvedTheme, accent], () => {
  const root = document.documentElement
  root.dataset.theme = resolvedTheme.value
  const [a, b] = (ACCENTS[accent.value] || ACCENTS.indigo)[resolvedTheme.value]
  root.style.setProperty('--accent', a)
  root.style.setProperty('--accent-2', b)
  const meta = document.querySelector('meta[name=theme-color]')
  if (meta) meta.content = resolvedTheme.value === 'dark' ? '#090a10' : '#f4f5fa'
}, { immediate: true })

export function setThemeMode(m) { themeMode.value = m; write('theme', m) }
export function setAccent(a) { accent.value = a; write('accent', a) }
export function toggleTheme() { setThemeMode(resolvedTheme.value === 'dark' ? 'light' : 'dark') }
