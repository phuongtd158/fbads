// Xuất bảng Tổng quan ra Excel (shared/xlsx.mjs + shared/exportOverview.mjs)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inflateRawSync } from 'node:zlib'
import { buildXlsx, colName, crc32, sheetName } from '../shared/xlsx.mjs'
import { overviewSheets, exportFileName } from '../shared/exportOverview.mjs'

// Đọc lại file zip (không nén) → { tên: chữ }
function unzip(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength), out = {}
  let p = 0
  while (dv.getUint32(p, true) === 0x04034b50) {
    const method = dv.getUint16(p + 8, true), crc = dv.getUint32(p + 14, true), size = dv.getUint32(p + 18, true)
    const nlen = dv.getUint16(p + 26, true), xlen = dv.getUint16(p + 28, true)
    const name = new TextDecoder().decode(buf.subarray(p + 30, p + 30 + nlen))
    let data = buf.subarray(p + 30 + nlen + xlen, p + 30 + nlen + xlen + size)
    if (method === 8) data = inflateRawSync(data)
    assert.equal(crc32(data), crc, `crc ${name}`)
    out[name] = new TextDecoder().decode(data)
    p += 30 + nlen + xlen + size
  }
  return out
}

test('tên cột Excel và tên sheet', () => {
  assert.equal(colName(0), 'A'); assert.equal(colName(25), 'Z'); assert.equal(colName(26), 'AA'); assert.equal(colName(27), 'AB'); assert.equal(colName(701), 'ZZ'); assert.equal(colName(702), 'AAA')
  assert.equal(sheetName('a/b:c[1]', 0), 'a b c 1')
  assert.equal(sheetName('', 2), 'Sheet3')
  assert.equal(sheetName('x'.repeat(40), 0).length, 31)
})

test('crc32 chuẩn', () => {
  assert.equal(crc32(new TextEncoder().encode('123456789')), 0xCBF43926)
})

test('file xlsx: đủ các phần, chữ có dấu, ký tự đặc biệt, số, ô trống', () => {
  const buf = buildXlsx([{ name: 'Chiến dịch', header: true, filter: true, cols: [{ width: 30 }, { width: 12 }], rows: [['Tên', 'Chi tiêu'], ['Camp <A> & "B" – tháng 9', { v: 1234567, fmt: 'int' }], ['Trống', null]] }])
  const f = unzip(buf)
  for (const k of ['[Content_Types].xml', '_rels/.rels', 'xl/workbook.xml', 'xl/_rels/workbook.xml.rels', 'xl/worksheets/sheet1.xml', 'xl/styles.xml']) assert.ok(f[k], k)
  const s = f['xl/worksheets/sheet1.xml']
  assert.match(s, /Camp &lt;A&gt; &amp; &quot;B&quot; – tháng 9/)
  assert.match(s, /<c r="B2" s="\d+"><v>1234567<\/v><\/c>/)
  assert.doesNotMatch(s, /r="B3"/) // ô trống không ghi
  assert.match(s, /state="frozen"/)
  assert.match(s, /<autoFilter ref="A1:B3"\/>/)
  assert.match(f['xl/workbook.xml'], /name="Chiến dịch"/)
  assert.match(f['xl/styles.xml'], /formatCode="0.00&quot;%&quot;"/)
})

const objs = {
  c1: { id: '111', name: 'Camp A', dailyBudget: 500000, accountName: 'TK 1', currency: 'VND' },
  c2: { id: '222', name: 'Camp B', dailyBudget: null, accountName: 'TK 2', currency: 'USD' },
}
const cols = [
  { key: 'budget', label: 'Ngân sách/ngày' }, { key: 'spend', label: 'Chi tiêu', money: true }, { key: 'results', label: 'Kết quả' },
  { key: 'cpa', label: 'CPA', money: true }, { key: 'roas', label: 'ROAS' }, { key: 'ctr', label: 'CTR' },
]
const base = { cols, level: 'campaign', deliveryLabel: (o) => (o.id === '111' ? 'Đang hoạt động' : 'Tắt'), currencyOf: (o) => o.currency }

test('bảng tổng quan: tiêu đề, số thật, định dạng theo loại tiền, ROAS không doanh thu để trống', () => {
  const items = [
    { o: objs.c1, m: { spend: 300000, results: 3, cpa: 100000, roas: 0, revenue: 0, ctr: 1.5 } },
    { o: { ...objs.c2, currency: 'VND' }, m: { spend: 0, results: 0, cpa: null, roas: null, revenue: 0, ctr: null } },
  ]
  const total = { spend: 300000, results: 3, cpa: 100000, roas: null, revenue: 0, ctr: 1.5, budget: 500000, budgetRows: 1 }
  const [sh, info] = overviewSheets({ ...base, items, showAcc: false, total, mixed: false, info: [['Khoảng ngày', 'Hôm nay']] })
  assert.deepEqual(sh.rows[0], ['Chiến dịch', 'ID', 'Phân phối', 'Ngân sách/ngày', 'Chi tiêu', 'Kết quả', 'CPA', 'ROAS', 'CTR (%)'])
  assert.deepEqual(sh.rows[1], ['Camp A', '111', 'Đang hoạt động', { v: 500000, fmt: 'int' }, { v: 300000, fmt: 'int' }, { v: 3, fmt: 'int' }, { v: 100000, fmt: 'int' }, null, { v: 1.5, fmt: 'pct' }])
  assert.deepEqual(sh.rows[2].slice(3), [null, { v: 0, fmt: 'int' }, { v: 0, fmt: 'int' }, null, null, null])
  const t = sh.rows[3]
  assert.deepEqual(t[0], { v: 'Tổng (2 mục)', bold: true })
  assert.deepEqual(t[3], { v: 500000, fmt: 'int', bold: true })
  assert.equal(t[7], null) // ROAS tổng không có doanh thu
  assert.equal(sh.filterRows, 3) // nút lọc không phủ dòng Tổng
  assert.equal(info.name, 'Thông tin')
  // ghi ra file được
  const f = unzip(buildXlsx([sh, info]))
  const s1 = f['xl/worksheets/sheet1.xml']
  assert.match(s1, /<autoFilter ref="A1:I3"\/>/)
  // có viền: ô trống (ROAS dòng 2) vẫn được ghi kèm kiểu có viền
  assert.match(s1, /<c r="H2" s="\d+"\/>/)
  assert.match(f['xl/styles.xml'], /<borders count="3">/)
  assert.match(f['xl/styles.xml'], /borderId="2"/)
  assert.match(f['xl/workbook.xml'], /'Chiến dịch'!\$A\$1:\$I\$3/)
})

test('nhiều loại tiền: có cột Loại tiền, USD 2 số lẻ, tổng không cộng cột tiền', () => {
  const items = [
    { o: objs.c1, m: { spend: 300000, results: 3, cpa: 100000, roas: 2, revenue: 600000, ctr: 1 } },
    { o: objs.c2, m: { spend: 12.5, results: 1, cpa: 12.5, roas: 1.2, revenue: 15, ctr: 2 } },
  ]
  const [sh] = overviewSheets({ ...base, items, showAcc: true, total: { spend: 300012.5, results: 4, revenue: 600015, roas: 2, cpa: 1, ctr: 1.2, budget: 500000, budgetRows: 1 }, mixed: true })
  assert.deepEqual(sh.rows[0].slice(0, 5), ['Chiến dịch', 'ID', 'Tài khoản', 'Phân phối', 'Loại tiền'])
  assert.deepEqual(sh.rows[2].slice(2, 7), ['TK 2', 'Tắt', 'USD', null, { v: 12.5, fmt: 'dec2' }])
  assert.deepEqual(sh.rows[2][9], { v: 1.2, fmt: 'ratio' })
  const t = sh.rows[3]
  assert.deepEqual(t.slice(5), [null, null, { v: 4, fmt: 'int', bold: true }, null, null, { v: 1.2, fmt: 'pct', bold: true }])
})

test('nhóm QC có cột chiến dịch; 1 dòng thì không có dòng Tổng', () => {
  const [sh] = overviewSheets({ ...base, level: 'adset', campaignName: () => 'Camp cha', items: [{ o: { ...objs.c1, name: 'Nhóm 1' }, m: { spend: 1, results: 0, revenue: 0 } }], showAcc: false, total: { spend: 1 }, mixed: false })
  assert.deepEqual(sh.rows[0].slice(0, 3), ['Nhóm quảng cáo', 'Chiến dịch', 'ID'])
  assert.deepEqual(sh.rows[1].slice(0, 2), ['Nhóm 1', 'Camp cha'])
  assert.equal(sh.rows.length, 2)
  assert.equal(sh.name, 'Nhóm QC')
})

test('tên file theo cấp và khoảng ngày', () => {
  assert.equal(exportFileName('campaign', '2026-09-01', '2026-09-30'), 'chien-dich_2026-09-01_2026-09-30.xlsx')
  assert.equal(exportFileName('adset', '2026-10-02', '2026-10-02'), 'nhom-qc_2026-10-02.xlsx')
  assert.equal(exportFileName('campaign', null, '2026-10-02'), 'chien-dich_2026-10-02.xlsx')
})
