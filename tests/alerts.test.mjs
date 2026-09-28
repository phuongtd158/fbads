// Cảnh báo bất thường (lib/alerts.js): tài khoản có vấn đề, quảng cáo bị từ chối, chi tiêu tăng vọt.
// fetch được thay bằng hàm giả nên không gọi Facebook hay Telegram thật.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { validateSettings } from '../shared/validate.mjs'

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-alerts-test-'))
process.env.DATA_DIR = dir
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const alerts = require('../lib/alerts')

fb._net.retryWaitMs = [0, 0]
let now = Date.parse('2026-09-28T08:00:00Z')
const realNow = Date.now
Date.now = () => now

// Dữ liệu Facebook giả, đổi được trong từng test
let fbData, sent, calls
const hours = (arr) => arr.map((spend, h) => ({ hourly_stats_aggregated_by_advertiser_time_zone: `${String(h).padStart(2, '0')}:00:00 - ${String(h).padStart(2, '0')}:59:59`, spend: String(spend) }))
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
globalThis.fetch = async (url, init = {}) => {
  const u = new URL(String(url))
  if (u.hostname === 'api.telegram.org') { sent.push(JSON.parse(init.body).text); return json({ ok: true }) }
  calls.push(u.pathname)
  if (u.pathname.endsWith('/ads')) return json({ data: fbData.ads })
  if (u.pathname.endsWith('/insights')) return json({ data: hours(u.searchParams.get('date_preset') === 'today' ? fbData.today : fbData.yesterday) })
  return json({ name: 'Shop A', currency: 'VND', account_status: fbData.status })
}

const today = { date: '2026-09-28', minutes: 600 }
beforeEach(() => {
  const d = store.get()
  d.logs.length = 0
  d.state.alerts = undefined
  Object.assign(d.settings, { mock: false, accessToken: 'EAAtoken-1234567890', adAccountIds: ['111'], adAccountId: '111', telegramToken: '1:x', telegramChatId: '42',
    alertAccount: true, alertDisapproved: true, alertSpike: true, spikePct: 50, spikeMinSpend: 100000 })
  fbData = { status: 1, ads: [], today: [50000, 50000, 50000], yesterday: [50000, 50000, 50000, 50000] }
  sent = []; calls = []
  fb.resetCache(); alerts.reset()
})
after(() => { Date.now = realNow; fs.rmSync(dir, { recursive: true, force: true }) })

test('mọi thứ bình thường: không báo gì, và 30 phút mới kiểm tra lại', async () => {
  await alerts.tickAlerts(today)
  assert.equal(sent.length, 0)
  const n = calls.length
  now += 10 * 60e3; await alerts.tickAlerts(today)
  assert.equal(calls.length, n, 'chưa đủ 30 phút: không gọi Facebook')
  now += 25 * 60e3; await alerts.tickAlerts(today)
  assert.ok(calls.length > n)
})

test('tài khoản bị vô hiệu hoá: báo 1 lần; hoạt động lại: báo đã hoạt động lại', async () => {
  fbData.status = 2
  await alerts.tickAlerts(today)
  assert.equal(sent.length, 1)
  assert.match(sent[0], /Tài khoản quảng cáo có vấn đề/)
  assert.match(sent[0], /Bị vô hiệu hoá/)
  now += 31 * 60e3; alerts.reset(); await alerts.tickAlerts(today)
  assert.equal(sent.length, 1, 'cùng trạng thái: không báo lại')
  fbData.status = 1
  now += 31 * 60e3; await alerts.tickAlerts(today)
  assert.equal(sent.length, 2)
  assert.match(sent[1], /đã hoạt động lại/)
  assert.equal(store.get().logs[0].source, 'Cảnh báo')
})

test('quảng cáo bị từ chối: báo quảng cáo mới, không báo lại quảng cáo đã báo', async () => {
  fbData.ads = [{ id: 'ad1', name: 'Video <sale>', campaign: { name: 'Camp 1' }, adset: { name: 'Nhóm 1' }, ad_review_feedback: { global: { ADULT: 'Nội dung người lớn' } } }]
  await alerts.tickAlerts(today)
  assert.equal(sent.length, 1)
  assert.match(sent[0], /1 quảng cáo bị từ chối/)
  assert.match(sent[0], /Video &lt;sale&gt;/, 'tên được thoát ký tự HTML')
  assert.match(sent[0], /Nội dung người lớn/)
  fbData.ads.push({ id: 'ad2', name: 'Ảnh 2', campaign: { name: 'Camp 1' } })
  now += 31 * 60e3; await alerts.tickAlerts(today)
  assert.equal(sent.length, 2)
  assert.match(sent[1], /1 quảng cáo bị từ chối/)
  assert.match(sent[1], /Ảnh 2/)
  assert.doesNotMatch(sent[1], /Video/)
})

test('chi tiêu tăng vọt so với cùng giờ hôm qua: báo 1 lần trong ngày', async () => {
  fbData.today = [100000, 100000, 100000]        // tới 03:00 đã chi 300k
  fbData.yesterday = [50000, 50000, 50000, 900000] // cùng giờ hôm qua (0–2h) chi 150k → tăng 100%
  await alerts.tickAlerts(today)
  assert.equal(sent.length, 1)
  assert.match(sent[0], /Chi tiêu tăng vọt/)
  assert.match(sent[0], /cao hơn 100%/)
  now += 31 * 60e3; await alerts.tickAlerts(today)
  assert.equal(sent.length, 1, 'đã báo hôm nay')
  now += 31 * 60e3; await alerts.tickAlerts({ date: '2026-09-29', minutes: 100 })
  assert.equal(sent.length, 2, 'ngày mới báo lại')
})

test('tăng vọt: dưới mức chi tối thiểu hoặc hôm qua chưa chi thì không báo', () => {
  const row = (spend, hour) => ({ spend, hour })
  assert.equal(alerts.spikeOf([row(90000, 0)], [row(10000, 0)], 50, 100000), null, 'chưa chi đủ mức tối thiểu')
  assert.equal(alerts.spikeOf([row(300000, 0)], [], 50, 100000), null, 'hôm qua chưa chi')
  assert.equal(alerts.spikeOf([row(140000, 0)], [row(100000, 0)], 50, 100000), null, 'tăng 40% < 50%')
  assert.deepEqual(alerts.spikeOf([row(200000, 1)], [row(50000, 0), row(50000, 1), row(500000, 2)], 50, 100000), { hour: 1, now: 200000, before: 100000, up: 100 })
})

test('tắt từng loại cảnh báo trong Cài đặt thì không hỏi Facebook phần đó', async () => {
  Object.assign(store.get().settings, { alertDisapproved: false, alertSpike: false })
  await alerts.tickAlerts(today)
  assert.ok(!calls.some((p) => p.endsWith('/ads') || p.endsWith('/insights')))
  Object.assign(store.get().settings, { alertAccount: false })
  calls = []; now += 31 * 60e3; await alerts.tickAlerts(today)
  assert.equal(calls.length, 0, 'tắt cả 3 loại: không gọi gì')
})

test('chế độ dùng thử: không kiểm tra', async () => {
  store.get().settings.mock = true
  await alerts.tickAlerts(today)
  assert.equal(calls.length, 0)
})

test('validate cài đặt cảnh báo', () => {
  assert.equal(validateSettings({ alertSpike: false, spikePct: 80, spikeMinSpend: 200000 }).ok, true)
  assert.equal(validateSettings({ spikePct: 5 }).ok, false)
  assert.equal(validateSettings({ spikeMinSpend: -1 }).ok, false)
})
