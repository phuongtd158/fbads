// Gọi Facebook khi mạng chập chờn: có timeout, lời gọi đọc được thử lại, lời gọi ghi thì không.
// fetch được thay bằng hàm giả nên không gọi Facebook thật.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-fbnet-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const notify = require('../lib/notify')

fb._net.timeoutMs = 50
fb._net.retryWaitMs = [0, 0]
notify._net.timeoutMs = 50

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
// Trả lời như Facebook treo: chỉ kết thúc khi bị huỷ bởi signal
// (hẹn giờ của AbortSignal.timeout không giữ tiến trình chạy, nên giữ bằng một setTimeout thường)
const hang = (init) => new Promise((_, reject) => {
  const keep = setTimeout(() => {}, 10e3)
  init.signal.addEventListener('abort', () => { clearTimeout(keep); reject(init.signal.reason) })
})

let script = [], calls = []
globalThis.fetch = async (url, init = {}) => {
  calls.push({ url: String(url), method: init.method || 'GET' })
  const step = script.shift() || 'ok'
  if (step === 'hang') return hang(init)
  if (step === 'neterr') throw new TypeError('fetch failed')
  if (step === '500') return json({ error: { code: 2, message: 'Service temporarily unavailable' } }, 500)
  if (step === 'rate') return json({ error: { code: 17, message: 'User request limit reached' } }, 400)
  if (String(url).includes('api.telegram.org')) return json({ ok: true })
  return json({ id: '1', name: 'Tài khoản', currency: 'VND', account_status: 1 })
}

beforeEach(() => {
  script = []; calls = []
  fb.resetCache()
  Object.assign(store.get().settings, { mock: false, accessToken: 'EAAtesttoken1234567890', adAccountIds: ['123'], adAccountId: '123' })
})

test('lời gọi đọc: treo hoặc lỗi mạng thì thử lại, lần sau được thì thành công', async () => {
  script = ['hang', 'neterr']
  const r = await fb.inspectToken('EAAtesttoken1234567890')
  assert.equal(r.valid, true, 'lần thứ 3 thành công')
  assert.equal(calls.length, 3)
})

test('lời gọi đọc: Facebook lỗi 5xx cả 3 lần thì báo lỗi, không thử quá 2 lần', async () => {
  script = ['500', '500', '500', '500']
  await assert.rejects(() => fb.inspectToken('EAAtesttoken1234567890'))
  assert.equal(calls.length, 3)
})

test('bị giới hạn số lần gọi thì không thử lại', async () => {
  script = ['rate']
  await assert.rejects(() => fb.inspectToken('EAAtesttoken1234567890'))
  assert.equal(calls.length, 1)
})

test('lời gọi ghi hết giờ: không thử lại, báo "không rõ kết quả"', async () => {
  script = ['hang']
  await assert.rejects(() => fb.setStatus('c1', false), (e) => {
    assert.equal(e.fb.timeout, true)
    assert.match(e.message, /Không rõ thao tác/)
    return true
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'POST')
})

test('lời gọi ghi lỗi mạng: không thử lại', async () => {
  script = ['neterr']
  await assert.rejects(() => fb.setBudget('c1', 500000))
  assert.equal(calls.length, 1)
})

test('Telegram treo thì báo lỗi sau thời gian chờ, không treo theo', async () => {
  Object.assign(store.get().settings, { telegramToken: '123:abc', telegramChatId: '42' })
  script = ['hang']
  const r = await notify.send('xin chào')
  assert.equal(r.results[0].ok, false)
  assert.match(r.results[0].error, /không trả lời/)
})
