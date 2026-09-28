// Canh tool chạy 24/7 (lib/watch.js): báo token sắp hết hạn/hỏng, vòng tự động lỗi liên tục/bị kẹt, /api/health.
// fetch được thay bằng hàm giả nên không gọi Facebook hay Telegram thật.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { spawn } from 'node:child_process'
import http from 'node:http'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-watch-test-'))
process.env.DATA_DIR = dir
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const watch = require('../lib/watch')

fb._net.retryWaitMs = [0, 0]
let now = Date.parse('2026-09-28T03:00:00Z')
const realNow = Date.now
Date.now = () => now

let token = { is_valid: true, expires_at: 0 }, tokenCalls = 0, sent = []
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
globalThis.fetch = async (url, init = {}) => {
  const u = String(url)
  if (u.includes('api.telegram.org')) { sent.push(JSON.parse(init.body).text); return json({ ok: true }) }
  if (u.includes('debug_token')) { tokenCalls++; return token.error ? json({ error: token.error }, 400) : json({ data: token }) }
  if (u.includes('/me')) return json({ error: { code: 190, message: 'Error validating access token: Session has expired' } }, 400)
  return json({})
}

const today = { date: '2026-09-28', minutes: 600 }
beforeEach(() => {
  Object.assign(store.get().settings, { mock: false, accessToken: 'EAAtoken-A-1234567890', adAccountIds: ['123'], telegramToken: '1:x', telegramChatId: '42' })
  store.get().state.tokenAlert = undefined
  token = { is_valid: true, expires_at: 0 }; tokenCalls = 0; sent = []
  fb.resetCache(); watch.reset()
})
after(() => { Date.now = realNow; fs.rmSync(dir, { recursive: true, force: true }) })

test('token không hết hạn: không báo; token còn 3 ngày: báo 1 lần mỗi ngày', async () => {
  await watch.tickToken(today)
  assert.equal(sent.length, 0)

  store.get().state.tokenAlert = undefined; watch.reset(); tokenCalls = 0
  token = { is_valid: true, expires_at: Math.floor(now / 1000) + 3 * 86400 + 3600 }
  await watch.tickToken(today)
  assert.equal(sent.length, 1)
  assert.match(sent[0], /sắp hết hạn/)
  assert.match(sent[0], /Còn 3 ngày/)
  await watch.tickToken(today) // cùng ngày: không hỏi lại Facebook, không báo lại
  assert.equal(sent.length, 1)
  assert.equal(tokenCalls, 1)
  await watch.tickToken({ date: '2026-09-29', minutes: 10 }) // ngày mới: báo lại
  assert.equal(sent.length, 2)
})

test('token còn 30 ngày: không báo', async () => {
  token = { is_valid: true, expires_at: Math.floor(now / 1000) + 30 * 86400 }
  await watch.tickToken(today)
  assert.equal(sent.length, 0)
})

test('Facebook báo token hỏng (190) khi đang chạy: báo ngay 1 lần; đổi token thì hết', async () => {
  await assert.rejects(() => fb.testConnection())
  assert.match(fb.tokenError(), /hết hạn/)
  await watch.tickToken(today)
  await watch.tickToken(today)
  assert.equal(sent.length, 1)
  assert.match(sent[0], /không còn dùng được/)

  store.get().settings.accessToken = 'EAAtoken-B-1234567890' // đổi token (kể cả qua "Gia hạn token", không gọi resetCache)
  assert.equal(fb.tokenError(), '')
  await watch.tickToken({ date: '2026-09-29', minutes: 10 })
  assert.equal(sent.length, 1, 'token mới còn tốt: không báo gì')
})

test('kiểm tra token bị lỗi mạng: thử lại sau 1 giờ, không gọi mỗi lượt', async () => {
  token = { error: { code: 2, message: 'Service temporarily unavailable' } }
  await watch.tickToken(today)
  const n = tokenCalls
  now += 60e3; await watch.tickToken(today)
  assert.equal(tokenCalls, n)
  token = { is_valid: false }
  now += 3600e3; await watch.tickToken(today)
  assert.equal(sent.length, 1)
  assert.match(sent[0], /không còn dùng được/)
})

test('chế độ dùng thử: không kiểm tra token', async () => {
  store.get().settings.mock = true
  await watch.tickToken(today)
  assert.equal(tokenCalls, 0)
})

test('lỗi 3 lượt liên tiếp: báo 1 lần; chạy lại bình thường: báo đã chạy lại', async () => {
  for (let i = 0; i < 5; i++) { watch.tickStarted(); await watch.tickDone(new Error('Chưa nhập Access Token')) }
  assert.equal(sent.length, 1)
  assert.match(sent[0], /lỗi 3 lượt liên tiếp/)
  watch.tickStarted(); await watch.tickDone(null)
  assert.equal(sent.length, 2)
  assert.match(sent[1], /chạy lại bình thường/)
  watch.tickStarted(); await watch.tickDone(null)
  assert.equal(sent.length, 2)
})

test('lượt chạy bị kẹt quá 5 phút: báo 1 lần và /health trả không ổn', async () => {
  watch.tickStarted(); await watch.tickDone(null)
  assert.equal(watch.health().ok, true)
  watch.tickStarted()
  now += 4 * 60e3; await watch.checkStall()
  assert.equal(sent.length, 0)
  now += 2 * 60e3; await watch.checkStall(); await watch.checkStall()
  assert.equal(sent.length, 1)
  assert.match(sent[0], /bị kẹt/)
  assert.equal(watch.health().ok, false)
  await watch.tickDone(null)
  assert.equal(watch.health().ok, true)
  assert.match(sent.at(-1), /chạy lại bình thường/)
})

test('không xong lượt nào trong 5 phút: /health trả không ổn', async () => {
  watch.tickStarted(); await watch.tickDone(null)
  now += 6 * 60e3
  assert.equal(watch.health().ok, false)
})

test('/api/health không cần đăng nhập, trả 200 khi vòng tự động đang chạy', async () => {
  const port = 10600 + Math.floor(Math.random() * 400)
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-health-test-'))
  const env = { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PORT: String(port), HOST: '127.0.0.1', DATA_DIR: data, APP_PASSWORD: crypto.randomBytes(12).toString('base64url') } // bật đăng nhập; mật khẩu ngẫu nhiên, test không cần biết
  const proc = spawn(process.execPath, ['server.js'], { cwd: path.resolve(import.meta.dirname, '..'), env })
  // fetch trong file này là hàm giả → gọi server bằng http
  const call = (method, p) => new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:${port}${p}`, { method }, (res) => {
      let b = ''; res.on('data', (d) => (b += d)); res.on('end', () => resolve({ status: res.statusCode, body: b }))
    })
    req.on('error', reject); req.end()
  })
  try {
    let up = false
    for (let i = 0; i < 60 && !up; i++) { try { await call('GET', '/api/auth'); up = true } catch { await new Promise((r) => setTimeout(r, 150)) } }
    assert.ok(up, 'server khởi động')
    const h = await call('GET', '/api/health')
    assert.equal(h.status, 200)
    assert.equal(JSON.parse(h.body).ok, true)
    assert.equal((await call('HEAD', '/api/health')).status, 200)
    assert.equal((await call('GET', '/api/state')).status, 401, 'các API khác vẫn cần đăng nhập')
  } finally {
    proc.kill()
    fs.rmSync(data, { recursive: true, force: true })
  }
})
