// Cột "Phân phối" giống Ads Manager: không chỉ là bật/tắt của chính mục đó mà còn xét các nhóm QC bên trong
// (vd camp đang bật nhưng mọi nhóm QC đều tắt → "Nhóm quảng cáo đang tắt").
// rank: thứ tự khi sắp xếp theo cột Phân phối (đang chạy lên đầu).
export const DELIVERY = {
  active: { label: 'Đang hoạt động', tone: 'success', rank: 0, running: true },
  learning: { label: 'Đang học', tone: 'info', rank: 1, running: true },
  review: { label: 'Đang xét duyệt', tone: 'warning', rank: 2 },
  issues: { label: 'Có vấn đề', tone: 'warning', rank: 3 },
  scheduled: { label: 'Đã lên lịch', tone: 'info', rank: 4 },
  adsetsOff: { label: 'Nhóm quảng cáo đang tắt', tone: 'neutral', rank: 5 },
  campaignOff: { label: 'Chiến dịch đang tắt', tone: 'neutral', rank: 6 },
  rejected: { label: 'Bị từ chối', tone: 'danger', rank: 7 },
  completed: { label: 'Hoàn tất', tone: 'neutral', rank: 8 },
  off: { label: 'Tắt', tone: 'neutral', rank: 9 },
  archived: { label: 'Lưu trữ', tone: 'neutral', rank: 10 },
  deleted: { label: 'Đã xoá', tone: 'neutral', rank: 11 },
}

// Phần chung cho camp và nhóm QC: trạng thái do chính mục đó quyết định. Trả về '' nếu cần xét tiếp.
function own(o) {
  const e = o.effective
  if (e === 'DELETED') return 'deleted'
  if (e === 'ARCHIVED') return 'archived'
  if (o.status === 'PAUSED') return 'off'
  if (e === 'CAMPAIGN_PAUSED') return 'campaignOff'
  if (e === 'ADSET_PAUSED') return 'adsetsOff'
  if (e === 'DISAPPROVED') return 'rejected'
  if (e === 'PENDING_REVIEW' || e === 'IN_PROCESS') return 'review'
  if (e === 'WITH_ISSUES') return 'issues'
  return ''
}

function adsetDelivery(a, now) {
  const k = own(a)
  if (k) return k
  if (a.endTime && a.endTime < now) return 'completed'
  if (a.startTime && a.startTime > now) return 'scheduled'
  if (a.learning) return 'learning'
  return a.effective === 'ACTIVE' ? 'active' : 'off'
}

function campaignDelivery(c, adsets, now) {
  const k = own(c)
  if (k) return k
  if (!adsets.length) return c.effective === 'ACTIVE' ? 'active' : 'off' // chưa có dữ liệu nhóm QC (vd dữ liệu giả)
  const ks = adsets.map((a) => adsetDelivery(a, now))
  const has = (x) => ks.includes(x)
  if (has('active') || has('learning')) return 'active'
  for (const x of ['review', 'issues', 'scheduled']) if (has(x)) return x
  const gone = (x) => ['completed', 'archived', 'deleted'].includes(x)
  if (has('completed') && ks.every(gone)) return 'completed'
  if (has('rejected') && ks.every((x) => x === 'rejected' || gone(x))) return 'rejected'
  return 'adsetsOff'
}

// Map id → khoá trong DELIVERY cho toàn bộ danh sách (camp + nhóm QC)
export function deliveryMap(objs, now = Date.now()) {
  const byCamp = {}
  for (const o of objs) if (o.level === 'adset') (byCamp[o.campaignId] ||= []).push(o)
  const out = {}
  for (const o of objs) out[o.id] = o.level === 'adset' ? adsetDelivery(o, now) : campaignDelivery(o, byCamp[o.id] || [], now)
  return out
}
