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
  { name: 'Chạy 6:00 – 23:00', d: { name: 'Chạy ban ngày', action: 'window', window: { on: '06:00', off: '23:00' } } },
  { name: 'Tăng 30% cuối tuần', d: { name: 'Tăng ngân sách cuối tuần', action: 'budget', mode: 'percent', value: 30, time: '07:00', days: [6, 0] } },
  { name: 'Giảm 50% ban đêm', d: { name: 'Giảm ngân sách ban đêm', action: 'budget', mode: 'percent', value: -50, time: '00:00' } },
]

export const METRICS = { cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Số kết quả', ctr: 'CTR (tỉ lệ nhấp, %)', cpc: 'CPC (chi phí mỗi nhấp)', cpm: 'CPM (chi phí 1000 hiển thị)', messages: 'Số tin nhắn mới', costPerMessage: 'Chi phí mỗi tin nhắn', leads: 'Số lead', costPerLead: 'Chi phí mỗi lead', frequency: 'Tần suất (lần xem/người)' }
// Tên ngắn dùng trong câu mô tả rule
export const METRIC_SHORT = { cpa: 'CPA', roas: 'ROAS', spend: 'Chi tiêu', results: 'Số kết quả', ctr: 'CTR', cpc: 'CPC', cpm: 'CPM', messages: 'Tin nhắn', costPerMessage: 'Chi phí/tin nhắn', leads: 'Lead', costPerLead: 'Chi phí/lead', frequency: 'Tần suất' }

export const RANGES = [
  { value: 'today', label: 'Hôm nay' },
  { value: 'yesterday', label: 'Hôm qua' },
  { value: 'last_3d', label: '3 ngày' },
  { value: 'last_7d', label: '7 ngày' },
]
export const RANGE_LABEL = { today: 'hôm nay', yesterday: 'hôm qua', last_3d: '3 ngày gần nhất', last_7d: '7 ngày gần nhất' }

export const RULE_PRESETS = [
  { name: 'Tắt camp CPA cao', d: { name: 'Tắt camp CPA cao', metric: 'cpa', op: '>', range: 'last_3d', value: 150000, minSpend: 300000, action: 'pause', cooldownHours: 24 } },
  { name: 'Tắt camp chi nhiều không ra kết quả', d: { name: 'Tắt camp không ra kết quả', metric: 'results', op: '<', range: 'last_3d', value: 1, minSpend: 500000, action: 'pause', cooldownHours: 24 } },
  { name: 'Tăng 20% khi ROAS tốt', d: { name: 'Tăng ngân sách khi ROAS tốt', metric: 'roas', op: '>', range: 'last_3d', value: 3, minSpend: 300000, action: 'increase', pct: 20, maxBudget: 2000000, cooldownHours: 24 } },
  { name: 'Giảm 20% khi ROAS thấp', d: { name: 'Giảm ngân sách khi ROAS thấp', metric: 'roas', op: '<', range: 'last_3d', value: 1.5, minSpend: 300000, action: 'decrease', pct: 20, minBudget: 100000, cooldownHours: 24 } },
  { name: 'Chỉ cảnh báo khi CPA cao', d: { name: 'Cảnh báo CPA cao', metric: 'cpa', op: '>', range: 'today', value: 150000, minSpend: 100000, action: 'notify', cooldownHours: 12 } },
  { name: 'Tắt khi CPA cao VÀ ROAS thấp', d: { name: 'Tắt camp CPA cao và ROAS thấp', conditions: [{ metric: 'cpa', op: '>', value: 150000 }, { metric: 'roas', op: '<', value: 1.5 }], match: 'all', range: 'last_3d', minSpend: 300000, action: 'pause', cooldownHours: 24 } },
  { name: 'Cắt lỗ hôm nay, mai chạy lại', d: { name: 'Cắt lỗ trong ngày', conditions: [{ metric: 'cpa', op: '>', value: 200000 }], match: 'all', range: 'today', minSpend: 300000, action: 'pause', cooldownHours: 24, resume: 'nextday', resumeAt: '06:00' } },
  { name: 'Tắt camp tin nhắn đắt', d: { name: 'Tắt camp tin nhắn đắt', conditions: [{ metric: 'costPerMessage', op: '>', value: 80000 }], match: 'all', range: 'last_3d', minSpend: 300000, action: 'pause', cooldownHours: 24 } },
  { name: 'Cắt lỗ: chi gấp đôi CPA mục tiêu chưa ra đơn', d: { name: 'Cắt lỗ theo CPA mục tiêu', conditions: [{ metric: 'spend', op: '>', vs: 'target', factor: 200 }, { metric: 'results', op: '<', value: 1 }], match: 'all', range: 'today', minSpend: 100000, action: 'pause', cooldownHours: 24, resume: 'nextday', resumeAt: '06:00' } },
  { name: 'Cảnh báo cháy quảng cáo (tần suất cao)', d: { name: 'Cảnh báo cháy quảng cáo', conditions: [{ metric: 'frequency', op: '>', value: 3.5 }, { metric: 'ctr', op: '<', value: 1 }], match: 'all', range: 'last_7d', minSpend: 200000, action: 'notify', cooldownHours: 24 } },
  { name: 'Tắt khi CPA vượt 120% mục tiêu', d: { name: 'Tắt camp CPA vượt mục tiêu', conditions: [{ metric: 'cpa', op: '>', vs: 'target', factor: 120 }], match: 'all', range: 'last_3d', minSpend: 300000, action: 'pause', cooldownHours: 24 } },
]

export const RESULT_ACTIONS = [
  ['purchase', 'Đơn hàng (web, app và trên Meta/Messenger)'],
  ['initiate_checkout', 'Bắt đầu thanh toán'],
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
