// Gợi ý cách khắc phục theo mã lỗi Facebook / nội dung lỗi của tool.
// Trả về [{ text, to?, cta? }] — `to` là đường dẫn trong app để sửa nhanh.

export const KIND_LABEL = { schedule: 'Lịch tự động', rule: 'Rule hiệu quả', manual: 'Thủ công', system: 'Hệ thống' }
export const MODE_LABEL = { mock: 'Dùng thử (dữ liệu giả)', dry: 'Chạy thử (không thay đổi camp thật)', live: 'Chạy thật' }

// Nhật ký cũ chưa có `kind`: suy ra từ chữ đầu của nguồn
export function kindOf(l) {
  if (l.kind) return l.kind
  const s = l.source || ''
  if (s.startsWith('Lịch')) return 'schedule'
  if (s.startsWith('Rule')) return 'rule'
  if (s.startsWith('Thủ công')) return 'manual'
  return 'system'
}

export function hintsFor(l) {
  const e = l.error || {}
  const msg = String(e.message || l.detail || '').toLowerCase()
  const out = []
  const add = (text, to, cta) => out.push({ text, ...(to ? { to, cta } : {}) })

  if (e.code === 190 || msg.includes('token đã hết hạn') || msg.includes('access token')) {
    add('Token Facebook đã hết hạn hoặc bị thu hồi. Tạo token mới và dán lại.', '/settings/connection', 'Đổi token')
    add('Dùng cách “Người dùng hệ thống” để có token không hết hạn.')
  } else if ([10, 200, 294, 278].includes(e.code) || msg.includes('chưa đủ quyền')) {
    add('Token thiếu quyền. Cần tick cả `ads_management` và `ads_read` khi tạo token.', '/settings/connection', 'Kiểm tra kết nối')
    add('Đảm bảo tài khoản quảng cáo đã được gán cho người dùng/token này với quyền quản lý chiến dịch.')
  } else if ([4, 17, 32, 613, 80004].includes(e.code) || msg.includes('giới hạn số lần')) {
    add('Facebook đang giới hạn số lần gọi. Chờ 5–10 phút rồi thử lại.')
    add('Nếu gặp thường xuyên, tăng “Kiểm tra rule mỗi (phút)” để tool gọi thưa hơn.', '/settings/general', 'Mở Cài đặt chung')
  } else if (e.subcode === 1487225 || msg.includes('cbo') || msg.includes('không có ngân sách')) {
    add('Camp đang dùng ngân sách chiến dịch (CBO), nhóm quảng cáo không có ngân sách riêng. Hãy đổi ngân sách ở cấp camp.')
    add('Sửa lịch/rule để chọn đúng camp có ngân sách, bỏ các mục CBO.', l.refId ? (kindOf(l) === 'rule' ? '/rules' : '/schedules') : undefined, 'Mở danh sách')
  } else if (e.network || msg.includes('không kết nối được')) {
    add('Tool không gọi được Facebook. Kiểm tra máy có internet, VPN hoặc tường lửa đang chặn không.')
    add('Nếu máy vừa tắt màn hình/ngủ, hãy tắt chế độ ngủ để lịch chạy ổn định.')
  } else if (msg.includes('không tìm thấy đối tượng') || /does not exist|nonexisting/.test(String(e.rawMessage || '').toLowerCase())) {
    add('Camp/nhóm này có thể đã bị xoá hoặc đổi cấp trên Facebook. Mở lịch/rule và chọn lại camp.', kindOf(l) === 'rule' ? '/rules' : '/schedules', 'Mở danh sách')
  } else if (e.code === 100) {
    add('Facebook từ chối tham số gửi lên (mã 100). Thường do ID không đúng, camp đã lưu trữ, hoặc giá trị ngân sách dưới mức tối thiểu của tài khoản.')
    add('Thử làm mới danh sách camp ở trang Tổng quan rồi chạy lại.', '/', 'Mở Tổng quan')
  } else if (e.code === 2635 || msg.includes('lưu trữ') || msg.includes('archived')) {
    add('Camp đã bị lưu trữ hoặc xoá nên không thể thay đổi. Bỏ nó khỏi lịch/rule.')
  } else {
    add('Bấm “Sao chép chi tiết” và gửi cho người hỗ trợ kèm mã `fbtrace_id` (nếu có) để tra nguyên nhân.')
    add('Thử lại sau vài phút. Nếu lỗi lặp lại, kiểm tra kết nối Facebook.', '/settings/connection', 'Kiểm tra kết nối')
  }
  return out
}
