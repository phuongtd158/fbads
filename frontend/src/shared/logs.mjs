// Gộp một dòng nhật ký nhận qua WebSocket vào danh sách đang hiển thị (mới nhất ở đầu).
// Dòng đã có (cùng id, vd. vừa được hoàn tác) thì thay tại chỗ; dòng mới thì chèn lên đầu, giữ tối đa `max` dòng.
export function mergeLog(list, entry, max = 300) {
  if (!entry || !entry.id) return list
  const i = list.findIndex((l) => l.id === entry.id)
  if (i >= 0) return list.map((l, j) => (j === i ? entry : l))
  return [entry, ...list].slice(0, max)
}
