// Ghi file Excel (.xlsx) tối giản, không cần thư viện: một .xlsx là file zip chứa vài file XML.
// Đủ dùng cho việc xuất bảng: chữ, số có định dạng (#,##0 / 0.00 / %), chữ đậm, độ rộng cột,
// cố định hàng tiêu đề và nút lọc trên tiêu đề. Dùng chung cho giao diện và kiểm thử.
//
// sheets: [{ name, cols: [{ width }], rows: [[cell…]], header: true (hàng đầu là tiêu đề), filter: true }]
// cell: null/'' (trống) | số | chữ | { v, fmt: 'int' | 'dec2' | 'ratio' | 'pct', bold }

const enc = new TextEncoder()
const FMT = { int: { id: 3 }, dec2: { id: 4 }, ratio: { id: 164, code: '0.00' }, pct: { id: 165, code: '0.00"%"' } }

const esc = (s) => String(s)
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '') // ký tự XML không cho phép
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

// Cột thứ i (0) → "A", 26 → "AA"
export function colName(i) {
  let s = ''
  for (i += 1; i > 0; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s
  return s
}

// Tên sheet: tối đa 31 ký tự, không chứa : \ / ? * [ ]
export const sheetName = (s, i) => String(s || '').replace(/[:\\/?*[\]]/g, ' ').trim().slice(0, 31) || `Sheet${i + 1}`

function styles() {
  const list = [{}] // kiểu 0: mặc định
  const ids = new Map([['|', 0]])
  const get = (fmt = '', bold = false, head = false) => {
    const k = `${fmt}|${bold ? 'b' : ''}${head ? 'h' : ''}`
    if (!ids.has(k)) { ids.set(k, list.length); list.push({ fmt, bold, head }) }
    return ids.get(k)
  }
  const xml = () => {
    const xfs = list.map((s) => {
      const numFmtId = s.fmt ? FMT[s.fmt].id : 0
      const fontId = s.bold || s.head ? 1 : 0
      const fillId = s.head ? 2 : 0
      const borderId = s.head ? 1 : 0
      return `<xf numFmtId="${numFmtId}" fontId="${fontId}" fillId="${fillId}" borderId="${borderId}" xfId="0"`
        + `${numFmtId ? ' applyNumberFormat="1"' : ''}${fontId ? ' applyFont="1"' : ''}${fillId ? ' applyFill="1" applyBorder="1"' : ''}`
        + `${s.head ? '><alignment vertical="center" wrapText="1"/></xf>' : '/>'}`
    })
    const custom = Object.values(FMT).filter((f) => f.code)
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
      + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
      + `<numFmts count="${custom.length}">${custom.map((f) => `<numFmt numFmtId="${f.id}" formatCode="${esc(f.code)}"/>`).join('')}</numFmts>`
      + '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>'
      + '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
      + '<fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF7"/><bgColor indexed="64"/></patternFill></fill></fills>'
      + '<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>'
      + '<border><left/><right/><top/><bottom style="thin"><color rgb="FFB4C0D3"/></bottom><diagonal/></border></borders>'
      + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
      + `<cellXfs count="${xfs.length}">${xfs.join('')}</cellXfs>`
      + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
      + '</styleSheet>'
  }
  return { get, xml }
}

// Số dòng (kể cả tiêu đề) có nút lọc; 0 = không lọc. filterRows: chừa dòng Tổng ở cuối ra ngoài vùng lọc
const filterRows = (sh) => (sh.header && sh.filter && (sh.rows || []).length > 1 ? Math.min(sh.filterRows || sh.rows.length, sh.rows.length) : 0)
const widthOf = (sh) => Math.max(1, ...(sh.rows || []).map((r) => r.length), (sh.cols || []).length)

function sheetXml(sh, st) {
  const rows = sh.rows || []
  const width = widthOf(sh)
  const out = []
  rows.forEach((row, r) => {
    const head = sh.header && r === 0
    const cells = []
    row.forEach((cell, c) => {
      const o = cell != null && typeof cell === 'object' ? cell : { v: cell }
      const v = o.v
      if (v == null || v === '' || (typeof v === 'number' && !Number.isFinite(v))) {
        if (head) cells.push(`<c r="${colName(c)}${r + 1}" s="${st.get('', false, true)}"/>`)
        return
      }
      const ref = `${colName(c)}${r + 1}`
      if (typeof v === 'number') {
        const s = st.get(o.fmt || '', !!o.bold, head)
        cells.push(`<c r="${ref}"${s ? ` s="${s}"` : ''}><v>${v}</v></c>`)
      } else {
        const s = st.get('', !!o.bold, head)
        cells.push(`<c r="${ref}"${s ? ` s="${s}"` : ''} t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`)
      }
    })
    out.push(`<row r="${r + 1}"${head ? ' ht="30" customHeight="1"' : ''}>${cells.join('')}</row>`)
  })
  const freeze = sh.header && rows.length
    ? '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft"/></sheetView></sheetViews>'
    : '<sheetViews><sheetView workbookViewId="0"/></sheetViews>'
  const cols = (sh.cols || []).length
    ? `<cols>${sh.cols.map((c, i) => `<col min="${i + 1}" max="${i + 1}" width="${c.width || 12}" customWidth="1"/>`).join('')}</cols>`
    : ''
  const fr = filterRows(sh)
  const filter = fr ? `<autoFilter ref="A1:${colName(width - 1)}${fr}"/>` : ''
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
    + `${freeze}<sheetFormatPr defaultRowHeight="15"/>${cols}<sheetData>${out.join('')}</sheetData>${filter}</worksheet>`
}

// Bảng CRC-32 (chuẩn zip)
const CRC = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 }
  return t
})()
export function crc32(bytes) {
  let c = 0xFFFFFFFF
  for (let i = 0; i < bytes.length; i++) c = CRC[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8)
  return (c ^ 0xFFFFFFFF) >>> 0
}

// Zip không nén (store): Excel đọc bình thường, code ngắn. files: [[tên, Uint8Array]]
export function zip(files) {
  const parts = [], central = []
  let offset = 0
  for (const [name, data] of files) {
    const nb = enc.encode(name), crc = crc32(data)
    const local = new DataView(new ArrayBuffer(30))
    local.setUint32(0, 0x04034b50, true); local.setUint16(4, 20, true); local.setUint16(6, 0x0800, true) // tên file UTF-8
    local.setUint16(8, 0, true); local.setUint16(10, 0, true); local.setUint16(12, 0x21, true) // giờ/ngày: 1980-01-01
    local.setUint32(14, crc, true); local.setUint32(18, data.length, true); local.setUint32(22, data.length, true)
    local.setUint16(26, nb.length, true); local.setUint16(28, 0, true)
    const cen = new DataView(new ArrayBuffer(46))
    cen.setUint32(0, 0x02014b50, true); cen.setUint16(4, 20, true); cen.setUint16(6, 20, true); cen.setUint16(8, 0x0800, true)
    cen.setUint16(10, 0, true); cen.setUint16(12, 0, true); cen.setUint16(14, 0x21, true)
    cen.setUint32(16, crc, true); cen.setUint32(20, data.length, true); cen.setUint32(24, data.length, true)
    cen.setUint16(28, nb.length, true); cen.setUint32(42, offset, true)
    parts.push(new Uint8Array(local.buffer), nb, data)
    central.push(new Uint8Array(cen.buffer), nb)
    offset += 30 + nb.length + data.length
  }
  const cenSize = central.reduce((t, p) => t + p.length, 0)
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true)
  end.setUint32(12, cenSize, true); end.setUint32(16, offset, true)
  const all = [...parts, ...central, new Uint8Array(end.buffer)]
  const out = new Uint8Array(all.reduce((t, p) => t + p.length, 0))
  let p = 0
  for (const a of all) { out.set(a, p); p += a.length }
  return out
}

export function buildXlsx(sheets) {
  const st = styles()
  const names = sheets.map((s, i) => sheetName(s.name, i))
  const sheetFiles = sheets.map((s, i) => [`xl/worksheets/sheet${i + 1}.xml`, enc.encode(sheetXml(s, st))])
  const head = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
  const files = [
    ['[Content_Types].xml', head + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      + sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')
      + '</Types>'],
    ['_rels/.rels', head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'],
    ['xl/workbook.xml', head + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
      + names.map((n, i) => `<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('') + '</sheets>'
      + (sheets.some(filterRows)
        ? `<definedNames>${sheets.map((s, i) => (filterRows(s)
          ? `<definedName name="_xlnm._FilterDatabase" localSheetId="${i}" hidden="1">'${esc(names[i].replace(/'/g, "''"))}'!$A$1:$${colName(widthOf(s) - 1)}$${filterRows(s)}</definedName>`
          : '')).join('')}</definedNames>`
        : '')
      + '</workbook>'],
    ['xl/_rels/workbook.xml.rels', head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')
      + `<Relationship Id="rId${sheets.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`],
    ...sheetFiles,
  ]
  files.push(['xl/styles.xml', enc.encode(st.xml())]) // sau cùng: các kiểu ô được gom trong lúc ghi sheet
  return zip(files.map(([n, d]) => [n, typeof d === 'string' ? enc.encode(d) : d]))
}
