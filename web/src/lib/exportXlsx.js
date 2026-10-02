// Xuất Excel: tạo file từ shared/ rồi tải về. Tải riêng (import động) khi bấm nút nên không làm nặng trang.
import { buildXlsx } from '../../../shared/xlsx.mjs'
export { overviewSheets, exportFileName } from '../../../shared/exportOverview.mjs'

export function downloadXlsx(sheets, fileName) {
  const blob = new Blob([buildXlsx(sheets)], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = fileName; a.style.display = 'none'
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
}
