export const fmt = (n) => (n == null || Number.isNaN(n) ? '–' : Math.round(n).toLocaleString('vi-VN'))
export const fmtDec = (n, d = 2) => (n == null || Number.isNaN(n) ? '–' : Number(n).toLocaleString('vi-VN', { minimumFractionDigits: d, maximumFractionDigits: d }))
export const fmtCompact = (n) => {
  if (n == null) return '–'
  const a = Math.abs(n)
  if (a >= 1e9) return (n / 1e9).toFixed(1).replace('.', ',') + ' tỷ'
  if (a >= 1e6) return (n / 1e6).toFixed(1).replace('.', ',').replace(',0', '') + ' tr'
  if (a >= 1e3) return Math.round(n / 1e3) + 'k'
  return String(Math.round(n))
}
export const pad2 = (n) => String(n).padStart(2, '0')
export const timeOf = (iso) => new Date(iso).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
export const dayLabel = (iso) => new Date(iso).toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit' })
