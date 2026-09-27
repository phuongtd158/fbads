// Định dạng nhẹ cho văn bản hướng dẫn: **đậm**, *nghiêng*, `mã`. Escape HTML trước rồi mới thay thẻ.
export function rich(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`(.+?)`/g, '<code>$1</code>')
}
