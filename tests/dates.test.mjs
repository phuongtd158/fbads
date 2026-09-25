import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  PRESETS, PRESET_IDS, isISODate, addDays, daysBetween, addMonths, minDate, todayIn, resolveRange, specKey, isToday, includesToday,
  fbParams, parseRange, rangeLabel, normalizeSpec, fmtDMY,
} from '../shared/dates.mjs'

const T = '2026-09-25' // Thứ Sáu
const r = (preset, today = T) => { const x = resolveRange({ preset }, today); return [x.since, x.until, x.days] }

test('ngày hợp lệ: loại ngày không có thật và sai định dạng', () => {
  assert.equal(isISODate('2026-09-25'), true)
  assert.equal(isISODate('2028-02-29'), true) // năm nhuận
  for (const bad of ['2026-02-30', '2026-13-01', '2026-9-1', '25/09/2026', '', null, undefined, 20260925]) assert.equal(isISODate(bad), false, String(bad))
})

test('cộng trừ ngày qua ranh giới tháng, năm, năm nhuận', () => {
  assert.equal(addDays('2026-09-25', -7), '2026-09-18')
  assert.equal(addDays('2026-01-03', -7), '2025-12-27')
  assert.equal(addDays('2028-03-01', -1), '2028-02-29')
  assert.equal(addDays('2026-12-31', 1), '2027-01-01')
  assert.equal(daysBetween('2026-09-18', '2026-09-24'), 7)
  assert.equal(daysBetween('2026-09-25', '2026-09-25'), 1)
  assert.equal(addMonths('2026-03-31', -1), '2026-02-28') // không tràn sang tháng sau
  assert.equal(addMonths('2026-01-15', -2), '2025-11-15')
  assert.equal(minDate('2026-09-25'), '2023-08-01') // 37 tháng trước, về đầu tháng
})

test('các khoảng có sẵn (hôm nay = Thứ Sáu 2026-09-25)', () => {
  assert.deepEqual(r('today'), ['2026-09-25', '2026-09-25', 1])
  assert.deepEqual(r('yesterday'), ['2026-09-24', '2026-09-24', 1])
  assert.deepEqual(r('today_yesterday'), ['2026-09-24', '2026-09-25', 2])
  assert.deepEqual(r('last_3d'), ['2026-09-22', '2026-09-24', 3]) // không tính hôm nay
  assert.deepEqual(r('last_7d'), ['2026-09-18', '2026-09-24', 7])
  assert.deepEqual(r('last_14d'), ['2026-09-11', '2026-09-24', 14])
  assert.deepEqual(r('last_28d'), ['2026-08-28', '2026-09-24', 28])
  assert.deepEqual(r('last_30d'), ['2026-08-26', '2026-09-24', 30])
  assert.deepEqual(r('this_week_mon_today'), ['2026-09-21', '2026-09-25', 5])
  assert.deepEqual(r('last_week_mon_sun'), ['2026-09-14', '2026-09-20', 7])
  assert.deepEqual(r('this_month'), ['2026-09-01', '2026-09-25', 25])
  assert.deepEqual(r('last_month'), ['2026-08-01', '2026-08-31', 31])
  assert.deepEqual(r('maximum'), [null, '2026-09-25', null])
})

test('tuần bắt đầu Thứ Hai: Chủ nhật thuộc tuần trước, Thứ Hai là ngày đầu tuần mới', () => {
  assert.deepEqual(r('this_week_mon_today', '2026-09-27'), ['2026-09-21', '2026-09-27', 7]) // Chủ nhật
  assert.deepEqual(r('last_week_mon_sun', '2026-09-27'), ['2026-09-14', '2026-09-20', 7])
  assert.deepEqual(r('this_week_mon_today', '2026-09-21'), ['2026-09-21', '2026-09-21', 1]) // Thứ Hai
  assert.deepEqual(r('last_week_mon_sun', '2026-09-21'), ['2026-09-14', '2026-09-20', 7])
})

test('sang năm và năm nhuận', () => {
  assert.deepEqual(r('last_7d', '2026-01-03'), ['2025-12-27', '2026-01-02', 7])
  assert.deepEqual(r('last_month', '2026-01-03'), ['2025-12-01', '2025-12-31', 31])
  assert.deepEqual(r('last_month', '2028-03-01'), ['2028-02-01', '2028-02-29', 29])
  assert.deepEqual(r('this_month', '2026-03-01'), ['2026-03-01', '2026-03-01', 1])
})

test('hôm nay theo múi giờ: 20:00 UTC đã là ngày mai ở Việt Nam', () => {
  const now = Date.UTC(2026, 8, 24, 20, 0)
  assert.equal(todayIn('Asia/Ho_Chi_Minh', now), '2026-09-25')
  assert.equal(todayIn('America/Los_Angeles', now), '2026-09-24')
  assert.match(todayIn('Khong/Co', now), /^\d{4}-\d{2}-\d{2}$/) // múi giờ sai → không ném lỗi
})

test('tham số gửi Facebook: có tên sẵn thì date_preset, không thì time_range', () => {
  assert.deepEqual(fbParams({ preset: 'last_7d' }, T), { date_preset: 'last_7d' })
  assert.deepEqual(fbParams({ preset: 'maximum' }, T), { date_preset: 'maximum' })
  assert.deepEqual(fbParams({ preset: 'today_yesterday' }, T), { time_range: '{"since":"2026-09-24","until":"2026-09-25"}' })
  assert.deepEqual(fbParams({ since: '2026-09-01', until: '2026-09-20' }, T), { time_range: '{"since":"2026-09-01","until":"2026-09-20"}' })
})

test('kiểm tra khoảng do người dùng gửi', () => {
  assert.deepEqual(parseRange({}, T), { ok: true, spec: { preset: 'today' }, key: 'p:today' }) // trống → hôm nay
  assert.deepEqual(parseRange({ range: 'last_7d' }, T).spec, { preset: 'last_7d' })
  assert.deepEqual(parseRange({ preset: 'this_month' }, T).spec, { preset: 'this_month' })
  const c = parseRange({ since: '2026-09-01', until: '2026-09-20' }, T)
  assert.deepEqual([c.ok, c.key], [true, 'r:2026-09-01_2026-09-20'])
  assert.deepEqual(parseRange({ since: '2026-09-25', until: '2026-09-25' }, T).ok, true) // đúng hôm nay
  assert.match(parseRange({ range: 'last_9d' }, T).error, /không được hỗ trợ/)
  assert.match(parseRange({ range: '../etc' }, T).error, /không được hỗ trợ/)
  assert.match(parseRange({ since: '2026-09-20', until: '2026-09-01' }, T).error, /trước hoặc bằng/)
  assert.match(parseRange({ since: '2026-09-20', until: '2026-09-26' }, T).error, /tương lai/)
  assert.match(parseRange({ since: '2020-01-01', until: '2020-01-31' }, T).error, /37 tháng/)
  assert.match(parseRange({ since: '2026-02-30', until: '2026-03-01' }, T).error, /năm-tháng-ngày/)
  assert.match(parseRange({ since: '2026-09-01' }, T).error, /năm-tháng-ngày/) // thiếu ngày kết thúc
  assert.equal(parseRange({ since: minDate(T), until: minDate(T) }, T).ok, true) // đúng ngày sớm nhất
})

test('nhãn hiển thị', () => {
  assert.deepEqual(rangeLabel({ preset: 'today' }, T), { title: 'Hôm nay', dates: '25/09/2026' })
  assert.deepEqual(rangeLabel({ preset: 'last_7d' }, T), { title: '7 ngày qua', dates: '18/09 – 24/09/2026' })
  assert.deepEqual(rangeLabel({ preset: 'maximum' }, T), { title: 'Tối đa', dates: 'đến 25/09/2026' })
  assert.deepEqual(rangeLabel({ since: '2026-09-01', until: '2026-09-20' }, T), { title: 'Tuỳ chọn', dates: '01/09 – 20/09/2026' })
  assert.deepEqual(rangeLabel({ since: '2026-09-10', until: '2026-09-10' }, T), { title: 'Tuỳ chọn', dates: '10/09/2026' })
  assert.deepEqual(rangeLabel({ since: '2025-12-20', until: '2026-01-05' }, T), { title: 'Tuỳ chọn', dates: '20/12/2025 – 05/01/2026' }) // khác năm: hiện đủ 2 năm
  assert.equal(fmtDMY('2026-09-05'), '05/09/2026')
})

test('khoá dùng cho bộ nhớ đệm, hôm nay, có gồm hôm nay không', () => {
  assert.equal(specKey({ preset: 'last_7d' }), 'p:last_7d')
  assert.equal(isToday({ preset: 'today' }), true)
  assert.equal(isToday({ preset: 'yesterday' }), false)
  assert.equal(isToday({ since: T, until: T }), false) // ngày tuỳ chọn không coi là "hôm nay" dù trùng ngày
  assert.equal(includesToday({ preset: 'this_month' }, T), true)
  assert.equal(includesToday({ preset: 'last_7d' }, T), false)
  assert.equal(includesToday({ since: '2026-09-20', until: T }, T), true)
})

test('đọc lại lựa chọn đã lưu: hỏng hoặc quá hạn thì về hôm nay', () => {
  assert.deepEqual(normalizeSpec({ preset: 'last_7d' }, T), { preset: 'last_7d' })
  assert.deepEqual(normalizeSpec({ since: '2026-09-01', until: '2026-09-20' }, T), { since: '2026-09-01', until: '2026-09-20' })
  for (const bad of [null, undefined, 'x', 5, {}, { preset: 'bậy' }, { since: '2026-09-20', until: '2026-09-01' }, { since: '2010-01-01', until: '2010-02-01' }]) {
    assert.deepEqual(normalizeSpec(bad, T), { preset: 'today' }, JSON.stringify(bad))
  }
})

test('mọi khoảng có sẵn đều tính được ngày và có nhãn', () => {
  assert.equal(PRESETS.length, PRESET_IDS.size)
  for (const p of PRESETS) {
    const x = resolveRange({ preset: p.id }, T)
    assert.ok(x.until <= T, p.id)
    assert.ok(rangeLabel({ preset: p.id }, T).title, p.id)
    if (x.since) assert.ok(x.since <= x.until, p.id)
  }
})
