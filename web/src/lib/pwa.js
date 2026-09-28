// Cài tool lên điện thoại như app.
//  - Android/Chrome, Edge: trình duyệt bắn sự kiện beforeinstallprompt → giữ lại để nút "Cài" gọi prompt() khi người dùng bấm.
//  - iPhone/iPad (Safari): Apple không có sự kiện này, chỉ cài được bằng Chia sẻ → Thêm vào MH chính → giao diện hiện hướng dẫn.
// Service worker (public/sw.js) chỉ để hiện trang offline; chỉ đăng ký ở bản build và khi trang chạy qua https/localhost.
import { reactive } from 'vue'

const standalone = () => {
  try { return matchMedia('(display-mode: standalone)').matches || navigator.standalone === true } catch { return false }
}
const ua = navigator.userAgent || ''
export const pwa = reactive({
  installed: standalone(),
  canPrompt: false,
  ios: /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1),
  secure: window.isSecureContext,
})

let deferred = null
export function initPwa() {
  window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferred = e; pwa.canPrompt = true })
  window.addEventListener('appinstalled', () => { deferred = null; pwa.canPrompt = false; pwa.installed = true })
  if (import.meta.env.PROD && 'serviceWorker' in navigator && window.isSecureContext) {
    window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch((e) => console.warn('Không đăng ký được service worker:', e && e.message)))
  }
}

// → 'accepted' | 'dismissed' | null (trình duyệt không cho cài bằng nút)
export async function promptInstall() {
  if (!deferred) return null
  const e = deferred
  deferred = null; pwa.canPrompt = false // mỗi sự kiện chỉ prompt được một lần
  await e.prompt()
  const { outcome } = await e.userChoice
  if (outcome === 'accepted') pwa.installed = true
  return outcome
}
