export const DAY_LABEL = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7']
export const DAY_ORDER = [1, 2, 3, 4, 5, 6, 0] // hiển thị T2 → CN

export const STATUS = {
  ACTIVE: { label: 'Đang chạy', tone: 'success' },
  PAUSED: { label: 'Tạm dừng', tone: 'neutral' },
  CAMPAIGN_PAUSED: { label: 'Camp đang tắt', tone: 'neutral' },
  ADSET_PAUSED: { label: 'Nhóm đang tắt', tone: 'neutral' },
  DISAPPROVED: { label: 'Bị từ chối', tone: 'danger' },
  WITH_ISSUES: { label: 'Có vấn đề', tone: 'warning' },
  PENDING_REVIEW: { label: 'Chờ duyệt', tone: 'warning' },
  IN_PROCESS: { label: 'Đang xử lý', tone: 'warning' },
  ARCHIVED: { label: 'Lưu trữ', tone: 'neutral' },
}

export const SCHEDULE_PRESETS = [
  { name: 'Bật camp 6:00 sáng', d: { name: 'Bật camp buổi sáng', action: 'on', time: '06:00' } },
  { name: 'Tắt camp 23:00', d: { name: 'Tắt camp buổi tối', action: 'off', time: '23:00' } },
  { name: 'Tăng 30% cuối tuần', d: { name: 'Tăng ngân sách cuối tuần', action: 'budget', mode: 'percent', value: 30, time: '07:00', days: [6, 0] } },
  { name: 'Giảm 50% ban đêm', d: { name: 'Giảm ngân sách ban đêm', action: 'budget', mode: 'percent', value: -50, time: '00:00' } },
]

export const METRICS = { cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Số kết quả' }

export const RULE_PRESETS = [
  { name: 'Tắt camp CPA cao', d: { name: 'Tắt camp CPA cao', metric: 'cpa', op: '>', value: 150000, minSpend: 100000, action: 'pause', cooldownHours: 24 } },
  { name: 'Tắt camp chi nhiều không ra kết quả', d: { name: 'Tắt camp không ra kết quả', metric: 'results', op: '<', value: 1, minSpend: 200000, action: 'pause', cooldownHours: 24 } },
  { name: 'Tăng 20% khi ROAS tốt', d: { name: 'Tăng ngân sách khi ROAS tốt', metric: 'roas', op: '>', value: 3, minSpend: 200000, action: 'increase', pct: 20, cooldownHours: 24 } },
  { name: 'Giảm 20% khi ROAS thấp', d: { name: 'Giảm ngân sách khi ROAS thấp', metric: 'roas', op: '<', value: 1.5, minSpend: 200000, action: 'decrease', pct: 20, cooldownHours: 24 } },
]

export const RESULT_ACTIONS = [
  ['purchase', 'Đơn hàng (purchase)'],
  ['lead', 'Khách để lại thông tin (lead)'],
  ['onsite_conversion.messaging_conversation_started_7d', 'Tin nhắn mới'],
  ['link_click', 'Lượt nhấp liên kết'],
]

export const ACCENTS = {
  indigo: { label: 'Chàm', light: ['#5b5bf0', '#8b5cf6'], dark: ['#8b8bff', '#a78bfa'] },
  blue: { label: 'Xanh dương', light: ['#2563eb', '#06b6d4'], dark: ['#60a5fa', '#22d3ee'] },
  emerald: { label: 'Ngọc', light: ['#059669', '#14b8a6'], dark: ['#34d399', '#2dd4bf'] },
  rose: { label: 'Hồng', light: ['#e11d48', '#f97316'], dark: ['#fb7185', '#fb923c'] },
  amber: { label: 'Hổ phách', light: ['#d97706', '#f43f5e'], dark: ['#fbbf24', '#fb7185'] },
}
