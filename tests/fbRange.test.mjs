// Số liệu theo khoảng ngày cho Tổng quan (fb.rangeData): tham số gửi Facebook, bộ nhớ đệm, bị giới hạn, dữ liệu giả.
// fetch và đồng hồ được thay bằng bản giả nên không gọi Facebook thật.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fbParams, resolveRange } from '../shared/dates.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-range-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')

let now = Date.parse('2026-09-25T05:00:00Z'), limited = false
Date.now = () => now
const insightCalls = [] // các lần gọi /insights: { level, params }
const reply = (body, headers = {}) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json', ...headers } })
globalThis.fetch = async (url) => {
  const u = new URL(String(url))
  if (limited) return reply({ error: { code: 80004, message: 'There have been too many calls' } }, { 'x-business-use-case-usage': JSON.stringify({ 1: [{ type: 'ads_management', call_count: 10, total_cputime: 1, total_time: 1, estimated_time_to_regain_access: 7 }] }) })
  if (u.pathname.endsWith('/campaigns')) return reply({ data: [{ id: 'c1', name: 'Camp', status: 'ACTIVE', effective_status: 'ACTIVE', daily_budget: '500000' }] })
  if (u.pathname.endsWith('/adsets')) return reply({ data: [] })
  if (u.pathname.endsWith('/insights')) {
    const level = u.searchParams.get('level')
    insightCalls.push({ level, preset: u.searchParams.get('date_preset'), range: u.searchParams.get('time_range') })
    // chi tiêu khác nhau theo khoảng để thấy đúng số liệu của từng khoảng
    const spend = u.searchParams.get('date_preset') === 'today' ? '1000' : u.searchParams.get('date_preset') === 'last_7d' ? '7000' : '3300'
    return reply({ data: level === 'campaign' ? [{ campaign_id: 'c1', spend, impressions: '2000', clicks: '40', actions: [{ action_type: 'purchase', value: '2' }] }] : [] })
  }
  return reply({ currency: 'VND' })
}
const T = '2026-09-25'
const q = (spec) => ({ key: spec.preset ? `p:${spec.preset}` : `r:${spec.since}_${spec.until}`, fbParams: fbParams(spec, T), days: resolveRange(spec, T).days })

beforeEach(() => {
  Object.assign(store.get().settings, { mock: false, accessToken: 'EAAtesttoken1234567890', adAccountId: '123', adAccountIds: ['123'], resultAction: 'purchase' })
  insightCalls.length = 0; limited = false
  now += 60 * 60e3 // qua hết thời gian bị chặn của test trước (trạng thái chặn nằm trong module)
  fb.resetCache()
})

test('khoảng có tên sẵn gửi date_preset, khoảng tuỳ chọn gửi time_range, và trả đúng số liệu của khoảng đó', async () => {
  const w = await fb.rangeData(q({ preset: 'last_7d' }))
  assert.equal(w.data.c1.spend, 7000)
  assert.deepEqual(w.data.c1.impressions, 2000)
  assert.deepEqual(insightCalls.map((c) => [c.level, c.preset, c.range]), [['campaign', 'last_7d', null], ['adset', 'last_7d', null]])

  insightCalls.length = 0
  const c = await fb.rangeData(q({ since: '2026-09-01', until: '2026-09-20' }))
  assert.equal(c.data.c1.spend, 3300)
  assert.deepEqual(insightCalls.map((x) => [x.preset, x.range]), [[null, '{"since":"2026-09-01","until":"2026-09-20"}'], [null, '{"since":"2026-09-01","until":"2026-09-20"}']])
})

test('hôm nay dùng lại danh sách camp (không gọi thêm insights riêng)', async () => {
  const t = await fb.rangeData({ key: 'p:today' }, true)
  assert.equal(t.data.c1.spend, 1000)
  assert.equal(insightCalls.filter((c) => c.preset === 'today').length, 2) // đúng 2 lần của danh sách camp (camp + nhóm QC), không nhân đôi
  const before = insightCalls.length
  await fb.rangeData({ key: 'p:today' }, false)
  assert.equal(insightCalls.length, before, 'tự làm mới trong 2 phút: dùng lại')
})

test('bộ nhớ đệm: tự làm mới dùng lại 4 phút, bấm Làm mới dùng lại 15 giây, sau đó tải lại', async () => {
  await fb.rangeData(q({ preset: 'last_7d' }))
  const n = insightCalls.length
  now += 3 * 60e3; await fb.rangeData(q({ preset: 'last_7d' }))
  assert.equal(insightCalls.length, n, 'dưới 4 phút: dùng lại')
  await fb.rangeData(q({ preset: 'last_7d' }), true)
  assert.equal(insightCalls.length, n + 2, 'bấm Làm mới sau 3 phút (quá 15 giây): tải lại')
  const m = insightCalls.length
  now += 5e3; await fb.rangeData(q({ preset: 'last_7d' }), true)
  assert.equal(insightCalls.length, m, 'bấm Làm mới liên tục trong 15 giây: dùng lại')
  now += 5 * 60e3; await fb.rangeData(q({ preset: 'last_7d' }))
  assert.ok(insightCalls.length > m, 'quá 4 phút: tải lại')
})

test('mỗi khoảng có bộ nhớ đệm riêng, đổi khoảng không làm hỏng số liệu của khoảng khác', async () => {
  const a = await fb.rangeData(q({ preset: 'last_7d' }))
  const b = await fb.rangeData(q({ since: '2026-09-01', until: '2026-09-20' }))
  const a2 = await fb.rangeData(q({ preset: 'last_7d' }))
  assert.equal(a.data.c1.spend, 7000)
  assert.equal(b.data.c1.spend, 3300)
  assert.equal(a2.data.c1.spend, 7000)
  assert.equal(insightCalls.length, 4, 'chỉ tải mỗi khoảng 1 lần (2 lần gọi × 2 khoảng)')
})

test('bị Facebook giới hạn: trả số liệu cũ đánh dấu stale; chưa có số nào thì báo lỗi dễ hiểu', async () => {
  await assert.rejects(async () => { limited = true; await fb.rangeData(q({ preset: 'last_14d' })) }, /giới hạn số lần gọi/)
  limited = false; now += 10 * 60e3; fb.resetCache() // hết thời gian bị chặn (7 phút)
  const ok = await fb.rangeData(q({ preset: 'last_7d' }))
  assert.equal(ok.stale, false)
  limited = true
  now += 10 * 60e3
  const stale = await fb.rangeData(q({ preset: 'last_7d' }))
  assert.equal(stale.stale, true)
  assert.equal(stale.data.c1.spend, 7000, 'vẫn là số liệu gần nhất')
  const k = insightCalls.length
  now += 60e3
  await fb.rangeData(q({ preset: 'last_7d' }), true) // đang bị chặn: không gọi thêm
  assert.equal(insightCalls.length, k)
  limited = false; now += 10 * 60e3
  assert.equal((await fb.rangeData(q({ preset: 'last_7d' }))).stale, false)
})

test('rule vẫn lấy số liệu theo tên khoảng như trước (rangeMetrics)', async () => {
  const m = await fb.rangeMetrics('last_7d', true)
  assert.equal(m.c1.spend, 7000)
  assert.equal(insightCalls[0].preset, 'last_7d')
  assert.equal((await fb.rangeMetrics('today', true)).c1.spend, 1000)
})

test('chế độ dùng thử: số liệu tăng theo số ngày và không gọi Facebook', async () => {
  Object.assign(store.get().settings, { mock: true })
  fb.resetMock(); fb.resetCache()
  const one = await fb.rangeData({ key: 'p:yesterday', days: 1 })
  const seven = await fb.rangeData({ key: 'p:last_7d', days: 7 })
  const max = await fb.rangeData({ key: 'p:maximum', days: null })
  assert.equal(insightCalls.length, 0)
  assert.equal(seven.data.mock_1.spend, one.data.mock_1.spend * 7)
  assert.ok(max.data.mock_1.spend > seven.data.mock_1.spend, 'tối đa (không có số ngày) vẫn có số liệu')
  // đổi giữa dùng thử và thật thì không dùng nhầm bộ nhớ đệm của chế độ kia
  Object.assign(store.get().settings, { mock: false })
  const real = await fb.rangeData({ key: 'p:last_7d', fbParams: { date_preset: 'last_7d' }, days: 7 })
  assert.equal(real.data.c1.spend, 7000)
})
