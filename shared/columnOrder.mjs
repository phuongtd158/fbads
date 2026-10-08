// Thứ tự cột của bảng do người dùng tự kéo thả. Dùng chung cho giao diện (web/src/lib/overviewColumns.js) và test.
// Danh sách cột là mảng key có thứ tự: thứ tự trong mảng = thứ tự hiển thị.

// Làm sạch danh sách đã lưu: bỏ key lạ / trùng, GIỮ thứ tự người dùng chọn, luôn có ít nhất 1 cột
export function cleanOrder(list, keys, defaults) {
  const ok = new Set(keys), out = []
  for (const k of Array.isArray(list) ? list : []) if (ok.has(k) && !out.includes(k)) out.push(k)
  return out.length ? out : [...defaults]
}

// Bật/tắt 1 cột: bật thì thêm vào cuối, tắt thì bỏ (không tắt cột cuối cùng)
export function toggleKey(list, key) {
  if (list.includes(key)) return list.length > 1 ? list.filter((k) => k !== key) : list
  return [...list, key]
}

// Đưa cột `key` tới vị trí `to` (chỉ số trong danh sách SAU khi đã nhấc cột ra). Sai key / vị trí thì trả nguyên danh sách.
export function moveKey(list, key, to) {
  const i = list.indexOf(key)
  if (i < 0) return list
  const out = list.filter((k) => k !== key)
  const j = Math.max(0, Math.min(out.length, to))
  if (j === i) return list
  out.splice(j, 0, key)
  return out
}

// Các cột đang bật xếp theo thứ tự chuẩn (nút "Đặt lại thứ tự cột")
export const canonicalOrder = (list, keys) => keys.filter((k) => list.includes(k))

// Thứ tự có khác thứ tự chuẩn không
export const isCanonical = (list, keys) => canonicalOrder(list, keys).every((k, i) => k === list[i])
