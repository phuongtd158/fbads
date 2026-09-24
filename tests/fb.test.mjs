// Tải danh sách camp khi Facebook giới hạn số lần gọi: dùng lại số liệu, tự ngưng gọi, báo số liệu cũ.
// fetch được thay bằng hàm giả nên không gọi Facebook thật.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-fb-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')

let now = Date.parse('2026-09-24T05:00:00Z'), calls = 0, limited = false, usagePct = 10
Date.now = () => now // giờ giả để thử thời gian dùng lại số liệu

const reply = (body, headers = {}) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json', ...headers } })
globalThis.fetch = async (url) => {
  calls++
  const u = String(url)
  const usage = { 'x-business-use-case-usage': JSON.stringify({ 123: [{ type: 'ads_management', call_count: usagePct, total_cputime: 1, total_time: 1, estimated_time_to_regain_access: limited ? 7 : 0, ads_api_access_tier: 'development_access' }] }) }
  if (limited) return reply({ error: { code: 80004, message: 'There have been too many calls' } }, usage)
  if (u.includes('/campaigns')) return reply({ data: [{ id: 'c1', name: 'Camp', status: 'ACTIVE', effective_status: 'ACTIVE', daily_budget: '500000' }] }, usage)
  if (u.includes('/adsets')) return reply({ data: [] }, usage)
  if (u.includes('/insights')) return reply({ data: [{ campaign_id: 'c1', spend: '1000', actions: [{ action_type: 'omni_purchase', value: '2' }] }] }, usage)
  return reply({ currency: 'VND' }, usage)
}

beforeEach(() => {
  Object.assign(store.get().settings, { mock: false, accessToken: 'EAAtesttoken1234567890', adAccountId: '123', resultAction: 'purchase' })
})

test('dùng lại số liệu vừa tải, giãn thời gian khi mức dùng API cao, giữ số cũ khi bị chặn', async () => {
  fb.resetCache()
  const first = await fb.listObjects(true)
  assert.equal(first[0].metrics.results, 2)
  assert.equal(fb.objectsMeta().usage.pct, 10)
  assert.equal(fb.objectsMeta().usage.tier, 'development_access')

  const n = calls
  now += 60e3; await fb.listObjects(false) // tự làm mới sau 1 phút: dùng lại
  assert.equal(calls, n, 'tự làm mới trong 2 phút: không gọi Facebook')
  now += 5e3; await fb.listObjects(true) // bấm Làm mới (đã 65 giây kể từ lần tải) → tải
  assert.ok(calls > n)
  const j = calls
  now += 5e3; await fb.listObjects(true) // bấm Làm mới liên tục trong 15 giây → dùng lại
  assert.equal(calls, j)

  usagePct = 70 // mức dùng cao → tự làm mới chỉ tải lại sau 5 phút
  now += 20e3; await fb.listObjects(true) // lần tải này nhận mức dùng 70%
  assert.equal(fb.objectsMeta().usage.pct, 70)
  const m = calls
  now += 3 * 60e3; await fb.listObjects(false)
  assert.equal(calls, m, 'không gọi Facebook khi chưa đủ 5 phút lúc mức dùng cao')

  limited = true // Facebook bắt đầu chặn
  now += 5 * 60e3
  const stale = await fb.listObjects(true)
  assert.equal(stale[0].metrics.results, 2, 'vẫn trả số liệu gần nhất')
  assert.equal(fb.objectsMeta().stale, true)
  assert.ok(fb.objectsMeta().blockedUntil > now, 'biết khi nào được gọi lại')

  const k = calls
  now += 60e3; await fb.listObjects(true)
  assert.equal(calls, k, 'đang bị chặn thì không gọi thêm')

  limited = false; usagePct = 10
  now += 8 * 60e3 // hết thời gian chặn (7 phút)
  await fb.listObjects(true)
  assert.equal(fb.objectsMeta().stale, false)
})

test('bị chặn mà chưa có số liệu nào → báo lỗi dễ hiểu', async () => {
  fb.resetCache()
  limited = true
  now += 60 * 60e3
  await assert.rejects(fb.listObjects(true), /giới hạn số lần gọi/)
  limited = false
  now += 60 * 60e3
})
