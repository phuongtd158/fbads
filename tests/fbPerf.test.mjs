// Tải danh sách nhanh hơn: request trùng chờ chung một lượt gọi Facebook, các tài khoản tải song song (tối đa 3 cùng lúc).
// fetch được thay bằng hàm giả (trả lời chậm một chút) nên không gọi Facebook thật.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-perf-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')

const IDS = ['101', '102', '103', '104']
const calls = [] // [đường dẫn, level]
let active = 0, maxActive = 0 // số tài khoản đang được tải danh sách camp cùng lúc
let rateLimitAcc = null // tài khoản trả lỗi giới hạn số lần gọi
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const reply = (body) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })
globalThis.fetch = async (url) => {
  const u = new URL(String(url)), m = u.pathname.match(/act_(\d+)(\/\w+)?$/)
  calls.push([u.pathname.replace(/^\/v[\d.]+\//, ''), u.searchParams.get('level') || ''])
  const id = m[1]
  if (!m[2]) return reply({ name: `TK ${id}`, currency: 'VND', account_status: 1 })
  if (m[2] === '/campaigns') {
    active++; maxActive = Math.max(maxActive, active)
    await sleep(30)
    active--
    if (id === rateLimitAcc) return reply({ error: { code: 17, message: 'User request limit reached' } })
    return reply({ data: [{ id: `c${id}`, name: `Camp ${id}`, status: 'ACTIVE', effective_status: 'ACTIVE', daily_budget: '100000' }] })
  }
  await sleep(10)
  if (m[2] === '/insights') return reply({ data: u.searchParams.get('level') === 'campaign' ? [{ campaign_id: `c${id}`, spend: '5000', actions: [] }] : [] })
  return reply({ data: [] })
}
const count = (p) => calls.filter(([c]) => c.endsWith(p)).length

function setup(ids = IDS) {
  Object.assign(store.get().settings, { mock: false, accessToken: 'EAAtesttoken1234567890', adAccountId: ids[0], adAccountIds: ids })
  fb.resetCache()
  calls.length = 0; maxActive = 0; rateLimitAcc = null
}

test('nhiều request cùng lúc chỉ gọi Facebook một lượt', async () => {
  setup(['101'])
  const [a, b, c] = await Promise.all([fb.listObjects(true), fb.listObjects(false), fb.listObjects(true)])
  assert.equal(count('/campaigns'), 1)
  assert.equal(a, b); assert.equal(b, c)
  // lượt sau (vẫn trong bộ nhớ đệm) cũng không gọi thêm
  await fb.listObjects(false)
  assert.equal(count('/campaigns'), 1)
})

test('4 tài khoản: tối đa 3 tài khoản tải cùng lúc, đủ cả 4, giữ thứ tự như Cài đặt', async () => {
  setup()
  const objs = await fb.listObjects(true)
  assert.equal(maxActive, 3)
  assert.deepEqual(objs.map((o) => o.accountId), IDS)
  const load = fb.objectsMeta().load
  assert.ok(load.ms >= 0)
  assert.deepEqual(load.accounts.map((a) => a.id), IDS)
  assert.deepEqual(Object.keys(load.accounts[0].parts).sort(), ['adsets', 'campaigns', 'info', 'insightsAdset', 'insightsCampaign'])
})

test('khoảng ngày: request trùng chờ chung, các tài khoản tải song song', async () => {
  setup()
  const q = { key: 'p:last_7d', fbParams: { date_preset: 'last_7d' }, days: 7 }
  const [a, b] = await Promise.all([fb.rangeData(q), fb.rangeData(q)])
  assert.equal(a, b)
  assert.equal(calls.filter(([p, l]) => p.endsWith('/insights') && l === 'campaign').length, IDS.length)
  assert.equal(a.data.c104.spend, 5000)
})

// Để cuối: lỗi giới hạn làm tool tạm ngưng gọi Facebook vài phút
test('bị giới hạn số lần gọi: dừng cả lượt, không bắt đầu tải thêm tài khoản', async () => {
  setup()
  rateLimitAcc = '101'
  await assert.rejects(fb.listObjects(true), (e) => fb.isRateLimited(e))
  assert.equal(calls.filter(([p]) => p === 'act_104/campaigns').length, 0)
})
