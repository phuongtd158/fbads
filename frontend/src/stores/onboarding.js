import { ref, computed } from 'vue'
import { state, modeOf } from './app'

// Danh sách "Bắt đầu nhanh" — tự tick theo trạng thái thật của tool
export const steps = computed(() => {
  const s = state.settings
  return [
    { id: 'connect', title: 'Kết nối Facebook', desc: 'Lấy token và chọn tài khoản quảng cáo của bạn.', done: !s.mock && !!s.has_accessToken && !!s.adAccountId, to: '/settings/connection', cta: 'Kết nối' },
    { id: 'schedule', title: 'Tạo lịch tự động đầu tiên', desc: 'Ví dụ: bật camp lúc 6:00, tắt lúc 23:00.', done: state.schedules.length > 0, to: '/schedules', cta: 'Tạo lịch' },
    { id: 'rule', title: 'Tạo rule bảo vệ ngân sách', desc: 'Ví dụ: tắt camp khi CPA quá cao.', done: state.rules.length > 0, to: '/rules', cta: 'Tạo rule' },
    { id: 'telegram', title: 'Nhận thông báo Telegram', desc: 'Không bắt buộc, nhưng rất tiện khi vắng máy.', done: !!(s.has_telegramToken && s.telegramChatId), to: '/settings/telegram', cta: 'Thiết lập', optional: true },
    { id: 'live', title: 'Chuyển sang Chạy thật', desc: 'Sau 2–3 ngày Chạy thử thấy Nhật ký đúng ý.', done: modeOf(s) === 'live', to: '/settings/mode', cta: 'Chuyển chế độ' },
  ]
})
export const doneCount = computed(() => steps.value.filter((x) => x.done).length)
export const allDone = computed(() => steps.value.every((x) => x.done || x.optional))
export const current = computed(() => steps.value.find((x) => !x.done))

const KEY = 'onboarding-hidden'
const read = () => { try { return localStorage.getItem(KEY) === '1' } catch { return false } }
export const hidden = ref(read())
export function setHidden(v) { hidden.value = v; try { localStorage.setItem(KEY, v ? '1' : '0') } catch { /* private mode */ } }
