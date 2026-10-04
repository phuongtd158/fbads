// Danh sách trang dùng chung cho Sidebar, thanh menu dưới (mobile) và Cài đặt → Giao diện
import { LayoutDashboard, CalendarClock, Zap, ScrollText, Settings, BookOpen, Building2 } from 'lucide-vue-next'
export * from '../../../shared/mobileTabs.mjs'

// label: tên đầy đủ (Sidebar), short: tên ngắn dưới icon ở thanh menu dưới
export const PAGES = [
  { id: 'overview', to: '/', label: 'Tổng quan', short: 'Tổng quan', icon: LayoutDashboard },
  { id: 'schedules', to: '/schedules', label: 'Lịch tự động', short: 'Lịch', icon: CalendarClock },
  { id: 'rules', to: '/rules', label: 'Rule hiệu quả', short: 'Rule', icon: Zap },
  { id: 'logs', to: '/logs', label: 'Nhật ký', short: 'Nhật ký', icon: ScrollText },
  { id: 'company', to: '/company', label: 'Báo cáo công ty', short: 'Báo cáo', icon: Building2 },
  { id: 'settings', to: '/settings', label: 'Cài đặt', short: 'Cài đặt', icon: Settings },
  { id: 'help', to: '/help', label: 'Hướng dẫn', short: 'Hướng dẫn', icon: BookOpen },
]
export const PAGE_BY_ID = Object.fromEntries(PAGES.map((p) => [p.id, p]))
