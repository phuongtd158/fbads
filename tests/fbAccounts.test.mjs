// Nhiều tài khoản quảng cáo: gộp danh sách, loại tiền theo từng tài khoản, tài khoản lỗi không làm hỏng các tài khoản khác.
// fetch được thay bằng hàm giả nên không gọi Facebook thật.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-acc-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')

const posts = []
const reply = (body) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })
const ACC = {
  111: { name: 'TK Việt', currency: 'VND', camps: [{ id: 'c1', name: 'Camp VN', status: 'ACTIVE', effective_status: 'ACTIVE', daily_budget: '500000' }], spend: '120000' },
  222: { name: 'TK Mỹ', currency: 'USD', camps: [{ id: 'c2', name: 'Camp US', status: 'ACTIVE', effective_status: 'ACTIVE', daily_budget: '2500' }], spend: '12.5' },
}
globalThis.fetch = async (url, init) => {
  const u = new URL(String(url))
  if (init && init.method === 'POST') { posts.push([u.pathname, Object.fromEntries(new URLSearchParams(String(init.body)))]); return reply({ success: true }) }
  const m = u.pathname.match(/act_(\d+)(\/\w+)?$/)
  if (!m) return reply({ error: { code: 100, message: 'không rõ' } })
  const a = ACC[m[1]]
  if (!a) return reply({ error: { code: 200, message: 'Permissions error' } }) // tài khoản 333: mất quyền
  if (!m[2]) return reply({ name: a.name, currency: a.currency, account_status: 1 })
  if (m[2] === '/campaigns') return reply({ data: a.camps })
  if (m[2] === '/adsets') return reply({ data: [] })
  if (m[2] === '/insights') return reply({ data: u.searchParams.get('level') === 'campaign' ? [{ campaign_id: a.camps[0].id, spend: a.spend, actions: [] }] : [] })
  return reply({ data: [] })
}

test('gộp camp của nhiều tài khoản, mỗi camp mang tài khoản + loại tiền của nó; tài khoản lỗi được báo riêng', async () => {
  Object.assign(store.get().settings, { mock: false, accessToken: 'EAAtesttoken1234567890', adAccountId: '111', adAccountIds: ['111', '222', '333'] })
  fb.resetCache()
  const objs = await fb.listObjects(true)
  assert.deepEqual(objs.map((o) => [o.id, o.accountId, o.accountName, o.currency, o.dailyBudget]), [
    ['c1', '111', 'TK Việt', 'VND', 500000],
    ['c2', '222', 'TK Mỹ', 'USD', 25], // 2500 cent = 25 USD
  ])
  const meta = fb.objectsMeta()
  assert.deepEqual(meta.accounts.map((a) => [a.id, a.name, a.currency]), [['111', 'TK Việt', 'VND'], ['222', 'TK Mỹ', 'USD'], ['333', '333', '']])
  assert.equal(meta.accountErrors.length, 1)
  assert.equal(meta.accountErrors[0].id, '333')
})

test('đổi ngân sách theo đúng đơn vị của tài khoản chứa camp', async () => {
  posts.length = 0
  await fb.setBudget('c2', 30) // 30 USD → 3000 cent
  await fb.setBudget('c1', 700000) // VND không có phần lẻ
  assert.deepEqual(posts.map(([p, b]) => [p.split('/').pop(), b.daily_budget]), [['c2', '3000'], ['c1', '700000']])
})

test('mọi tài khoản đều lỗi → báo lỗi', async () => {
  Object.assign(store.get().settings, { adAccountIds: ['333'], adAccountId: '333' })
  fb.resetCache()
  await assert.rejects(fb.listObjects(true))
})
