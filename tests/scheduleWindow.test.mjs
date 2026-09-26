// Lịch "Bật + tắt theo giờ" (khung giờ), lọc theo trạng thái / nhiều từ khoá, cảnh báo khi chọn mục cho lịch.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateSchedule, scheduleEvents, eventOnDay, windowIsOn, targetWarnings } from '../shared/validate.mjs'
import { matchFilter, describeFilter, readFilter } from '../shared/bulk.mjs'

const objs = [
  { id: 'c1', level: 'campaign', name: '[Sale] Camp A', status: 'ACTIVE', effective: 'ACTIVE', dailyBudget: 500000 },
  { id: 'c2', level: 'campaign', name: '[Lead] Camp B', status: 'PAUSED', effective: 'PAUSED', dailyBudget: null },
  { id: 'a1', level: 'adset', campaignId: 'c2', name: 'Nhóm 1', status: 'PAUSED', effective: 'PAUSED', dailyBudget: 100000 },
  { id: 'a2', level: 'adset', campaignId: 'c1', name: 'Nhóm 1', status: 'PAUSED', effective: 'PAUSED', dailyBudget: 100000 },
  { id: 'a3', level: 'adset', campaignId: 'c1', name: 'Nhóm 2', status: 'PAUSED', effective: 'PAUSED', dailyBudget: 100000 },
]
const win = { name: 'Ban ngày', action: 'window', window: { on: '06:00', off: '23:00' }, days: [1, 2, 3, 4, 5], targets: ['c1'] }

test('khung giờ: 1 lần bật + 1 lần tắt; tắt sau nửa đêm thuộc ngày hôm trước', () => {
  assert.deepEqual(scheduleEvents(win), [{ time: '06:00', action: 'on', prevDay: false }, { time: '23:00', action: 'off', prevDay: false }])
  const night = { ...win, window: { on: '20:00', off: '02:00' }, days: [5] } // chỉ thứ 6
  const off = scheduleEvents(night)[1]
  assert.equal(off.prevDay, true)
  assert.equal(eventOnDay(night, off, 6), true) // tắt 02:00 sáng thứ 7
  assert.equal(eventOnDay(night, off, 5), false)
  assert.equal(windowIsOn(night, 5, 21 * 60), true)
  assert.equal(windowIsOn(night, 6, 60), true)
  assert.equal(windowIsOn(night, 6, 3 * 60), false)
  assert.equal(windowIsOn(win, 1, 12 * 60), true)
  assert.equal(windowIsOn(win, 0, 12 * 60), false) // CN không chạy
  assert.deepEqual(scheduleEvents({ action: 'on', times: ['06:00', '12:00'] }).map((e) => e.action), ['on', 'on'])
})

test('khung giờ: validate lưu window + times, báo lỗi khi thiếu / trùng giờ', () => {
  const r = validateSchedule(win, { objs })
  assert.equal(r.ok, true)
  assert.deepEqual(r.value.window, { on: '06:00', off: '23:00' })
  assert.deepEqual(r.value.times, ['06:00', '23:00'])
  assert.ok(validateSchedule({ ...win, window: { on: '06:00', off: '06:00' } }, { objs }).errors.time)
  assert.ok(validateSchedule({ ...win, window: { on: '06:00' } }, { objs }).errors.time)
  assert.ok(validateSchedule({ ...win, window: { on: '22:00', off: '02:00' } }, { objs }).warnings.some((w) => w.includes('hôm sau')))
  assert.equal(validateSchedule({ ...win, action: 'on', times: ['06:00'] }, { objs }).value.window, undefined)
})

test('khung giờ: xung đột với lịch tắt cùng giờ bật, trùng lịch khung giờ giống hệt', () => {
  const others = [{ id: 'x', name: 'Tắt sáng', action: 'off', times: ['06:00'], days: [1], targets: ['c1'], enabled: true }]
  assert.ok(validateSchedule(win, { objs, schedules: others }).errors.conflict)
  const same = [{ id: 'y', name: 'Cũ', ...win, times: ['06:00', '23:00'], enabled: true }]
  assert.ok(validateSchedule(win, { objs, schedules: same }).errors.conflict.includes('giống hệt'))
  // lịch bật 23:00 ngược với lần tắt 23:00 của khung giờ
  assert.ok(validateSchedule({ ...okOn, times: ['23:00'] }, { objs, schedules: [{ id: 'y', ...win, enabled: true }] }).errors.conflict)
})
const okOn = { name: 'Bật', action: 'on', days: [1], targets: ['c1'] }

test('khung giờ theo điều kiện phải lọc trạng thái “Tất cả”', () => {
  const base = { ...win, targetMode: 'filter', targets: [] }
  assert.ok(validateSchedule({ ...base, filter: { level: 'campaign', op: 'any', name: 'x', status: 'off' } }, { objs }).errors.filter)
  assert.equal(validateSchedule({ ...base, filter: { level: 'campaign', op: 'any', name: 'x', status: 'all' } }, { objs }).ok, true)
})

test('lọc theo trạng thái và nhiều từ khoá; dữ liệu cũ onlyRunning vẫn đọc được', () => {
  assert.deepEqual(matchFilter(objs, { level: 'campaign', status: 'off' }).map((o) => o.id), ['c2'])
  assert.deepEqual(matchFilter(objs.filter((o) => o.level === 'campaign'), { level: 'campaign', onlyRunning: true }).map((o) => o.id), ['c1'])
  assert.deepEqual(matchFilter(objs, { level: 'campaign', status: 'running' }).map((o) => o.id), []) // camp bật nhưng mọi nhóm QC tắt: không tính đang chạy
  assert.deepEqual(matchFilter(objs, { level: 'campaign', name: 'sale, lead' }).map((o) => o.id), ['c1', 'c2'])
  assert.deepEqual(matchFilter(objs, { level: 'campaign', name: ' , ' }).map((o) => o.id), ['c1', 'c2'])
  assert.equal(describeFilter({ level: 'campaign', op: 'any', name: 'Sale, Lead', status: 'off' }), 'Chiến dịch · tên chứa “Sale” hoặc “Lead” · đang tắt')
  assert.equal(readFilter({ level: 'campaign', op: 'any', onlyRunning: true }).filter.status, 'running')
  const v = validateSchedule({ ...okOn, times: ['06:00'], targetMode: 'filter', filter: { level: 'campaign', op: 'any', onlyRunning: true } }, { objs })
  assert.equal(v.value.filter.status, 'running')
  assert.ok(v.warnings.some((w) => w.includes('không có gì để bật')))
})

test('cảnh báo: bật camp có mọi nhóm QC đang tắt, bật nhóm QC thuộc camp đang tắt, tắt cả camp lẫn nhóm con', () => {
  assert.ok(targetWarnings(['c1'], objs, true).some((w) => w.includes('Mọi nhóm QC trong “[Sale] Camp A”')))
  assert.equal(targetWarnings(['c1', 'a2'], objs, true).length, 0) // bật kèm nhóm QC thì ổn
  assert.ok(targetWarnings(['a1'], objs, true).some((w) => w.includes('thuộc chiến dịch đang tắt')))
  assert.equal(targetWarnings(['a1', 'c2'], objs, true).length, 0)
  assert.ok(targetWarnings(['c1', 'a2'], objs, false).some((w) => w.includes('cả chiến dịch lẫn nhóm QC')))
  assert.ok(validateSchedule({ ...okOn, times: ['06:00'] }, { objs }).warnings.some((w) => w.includes('Mọi nhóm QC')))
})

/* ------------------------------------------------------------- engine */
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')
const S = () => store.get()
const camp = async (id) => (await fb.listObjects(true)).find((o) => o.id === id)

beforeEach(() => {
  const d = S()
  d.logs.length = 0; d.rules.length = 0; d.schedules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {} }
  Object.assign(d.settings, { mock: true, dryRun: false, skipLearning: true, dailyChangeCapPct: 30, killSwitchEnabled: false, dailySpendLimit: 0 })
  fb.resetMock(); fb.resetCache()
})

const nowParts = (offsetMin = 0) => {
  const tz = S().settings.timezone || 'Asia/Ho_Chi_Minh'
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23', weekday: 'short' })
    .formatToParts(new Date(Date.now() + offsetMin * 60e3)).map((x) => [x.type, x.value]))
  return { hm: `${p.hour}:${p.minute}`, day: { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 }[p.weekday] }
}

test('engine: tới giờ tắt của khung giờ thì tắt, chưa tới giờ bật thì chưa bật', async () => {
  const off = nowParts(-2), on = nowParts(120)
  if (on.hm < off.hm) return // gần nửa đêm: bỏ qua cho khỏi lệch ngày
  S().schedules.push({ id: 'w1', name: 'Khung', action: 'window', window: { on: on.hm, off: off.hm }, times: [off.hm, on.hm].sort(), days: [0, 1, 2, 3, 4, 5, 6], targetMode: 'list', targets: ['mock_1'], enabled: true })
  // giờ tắt < giờ bật → tắt thuộc "hôm trước", hôm trước cũng nằm trong days nên vẫn chạy
  await engine.tickSchedules()
  assert.equal((await camp('mock_1')).status, 'PAUSED')
  const logs = S().logs.filter((l) => l.refId === 'w1')
  assert.equal(logs.length, 1)
  assert.equal(logs[0].action.type, 'off')
})

test('engine: Chạy ngay lịch khung giờ đưa camp về đúng trạng thái lúc này', async () => {
  const d = nowParts()
  const inside = { id: 'w2', name: 'Đang trong giờ', action: 'window', window: { on: '00:00', off: '23:59' }, days: [0, 1, 2, 3, 4, 5, 6], targetMode: 'list', targets: ['mock_3'], enabled: true }
  if (d.hm === '23:59') return
  await engine.runSchedule(inside)
  assert.equal((await camp('mock_3')).status, 'ACTIVE') // mock_3 đang tắt → bật
  const outside = { ...inside, id: 'w3', days: [(d.day + 3) % 7] } // hôm nay (và hôm qua) không chạy → ngoài khung
  await engine.runSchedule(outside)
  assert.equal((await camp('mock_3')).status, 'PAUSED')
})
