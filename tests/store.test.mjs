// Kiểm thử lớp lưu trữ: mã hoá, Upstash (server giả chạy cục bộ), chống ghi đè khi lỗi. Không còn lưu file.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import http from 'node:http'

process.env.STORE_MEMORY = '1' // bản store tạo sẵn khi nạp module chạy ở chế độ bộ nhớ
delete process.env.UPSTASH_REDIS_REST_URL; delete process.env.UPSTASH_REDIS_REST_TOKEN
const require = createRequire(import.meta.url)
const remote = require('../lib/remote')
const { create } = require('../lib/store')

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

// ----- Server Upstash giả: POST / với ["GET"|"SET", ...]; đếm lệnh, có thể cố tình trả lỗi -----
async function fake() {
  const kv = new Map(), calls = []
  const s = { kv, calls, failNext: 0, down: false, token: 'tok-that-1234' }
  const server = http.createServer((req, res) => {
    let b = ''
    req.on('data', (c) => (b += c))
    req.on('end', () => {
      if (s.down) return req.socket.destroy()
      if (req.headers.authorization !== `Bearer ${s.token}`) { res.writeHead(401); return res.end(JSON.stringify({ error: 'Unauthorized' })) }
      const [cmd, k, v] = JSON.parse(b)
      calls.push([cmd, k])
      if (s.failNext > 0) { s.failNext--; res.writeHead(500); return res.end(JSON.stringify({ error: 'loi gia lap' })) }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      if (cmd === 'GET') return res.end(JSON.stringify({ result: kv.has(k) ? kv.get(k) : null }))
      if (cmd === 'SET') { kv.set(k, v); return res.end(JSON.stringify({ result: 'OK' })) }
      res.end(JSON.stringify({ error: 'lenh la' }))
    })
  })
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  s.url = `http://127.0.0.1:${server.address().port}`
  s.close = () => server.close()
  s.sets = () => calls.filter((c) => c[0] === 'SET' && c[1] === 'fbads:data').length
  return s
}
const envFor = (s, key = 'khoa-bi-mat-rat-dai-123') => ({ UPSTASH_REDIS_REST_URL: s.url, UPSTASH_REDIS_REST_TOKEN: s.token, DATA_KEY: key })
const mk = (s, extra = {}) => create({ env: envFor(s, extra.key), debounceMs: 20, retryBaseMs: 20, startRetryMs: 5, ...extra.opts })

// ----- Mã hoá -----
test('mã hoá: khứ hồi đúng, không lộ nội dung, mỗi lần một bản khác nhau', () => {
  const json = JSON.stringify({ settings: { accessToken: 'EAAB-token-bi-mat' } })
  const a = remote.encode(json, 'khoa-bi-mat-rat-dai-123'), b = remote.encode(json, 'khoa-bi-mat-rat-dai-123')
  assert.notEqual(a, b)
  assert.ok(a.startsWith('v1:'))
  assert.ok(!Buffer.from(a.slice(3), 'base64').toString('latin1').includes('EAAB-token'))
  assert.equal(remote.decode(a, 'khoa-bi-mat-rat-dai-123'), json)
})

test('mã hoá: sai khoá hoặc bị sửa đều bị từ chối (BAD_KEY), định dạng lạ → BAD_FORMAT', () => {
  const enc = remote.encode('{"a":1}', 'khoa-bi-mat-rat-dai-123')
  assert.throws(() => remote.decode(enc, 'khoa-khac-hoan-toan-999'), { code: 'BAD_KEY' })
  const buf = Buffer.from(enc.slice(3), 'base64'); buf[buf.length - 1] ^= 1
  assert.throws(() => remote.decode('v1:' + buf.toString('base64'), 'khoa-bi-mat-rat-dai-123'), { code: 'BAD_KEY' })
  assert.throws(() => remote.decode('{"json":"tho"}', 'khoa-bi-mat-rat-dai-123'), { code: 'BAD_FORMAT' })
  assert.throws(() => remote.decode('v1:abc', 'khoa-bi-mat-rat-dai-123'), { code: 'BAD_FORMAT' })
})

// ----- Cấu hình -----
test('cấu hình: không đặt gì → null; thiếu/sai → lỗi rõ ràng', () => {
  assert.equal(remote.config({}), null)
  assert.throws(() => remote.config({ UPSTASH_REDIS_REST_URL: 'https://a.upstash.io' }), /cả UPSTASH/)
  assert.throws(() => remote.config({ UPSTASH_REDIS_REST_URL: 'khong-phai-url', UPSTASH_REDIS_REST_TOKEN: 't' }), /hợp lệ/)
  assert.throws(() => remote.config({ UPSTASH_REDIS_REST_URL: 'http://a.upstash.io', UPSTASH_REDIS_REST_TOKEN: 't', DATA_KEY: 'x'.repeat(20) }), /https/)
  assert.throws(() => remote.config({ UPSTASH_REDIS_REST_URL: 'https://a.upstash.io', UPSTASH_REDIS_REST_TOKEN: 't', DATA_KEY: 'ngan' }), /DATA_KEY/)
  assert.ok(remote.config({ UPSTASH_REDIS_REST_URL: 'http://127.0.0.1:9', UPSTASH_REDIS_REST_TOKEN: 't', DATA_KEY: 'x'.repeat(20) }))
  assert.ok(remote.config({ UPSTASH_REDIS_REST_URL: 'https://a.upstash.io', UPSTASH_REDIS_REST_TOKEN: 't', DATA_KEY: 'x'.repeat(20) }))
})

test('cấu hình sai thì init() từ chối, không âm thầm chạy chế độ khác', async () => {
  const st = create({ env: { UPSTASH_REDIS_REST_URL: 'https://a.upstash.io', UPSTASH_REDIS_REST_TOKEN: 't' } })
  await assert.rejects(st.init(), /DATA_KEY/)
})

// ----- Upstash -----
test('lần đầu: dữ liệu trống được ghi (đã mã hoá) lên Upstash', async () => {
  const s = await fake()
  const st = mk(s); await st.init()
  assert.equal(st.get().rules.length, 0)
  assert.equal(s.sets(), 1)
  assert.ok(!JSON.stringify([...s.kv.values()]).includes('accessToken'))
  s.close()
})

test('ghi gộp: nhiều thay đổi liên tiếp chỉ tốn 1 lệnh SET; khởi động lại vẫn còn dữ liệu', async () => {
  const s = await fake()
  const a = mk(s); await a.init()
  const before = s.sets()
  a.get().settings.accessToken = 'EAAB-bi-mat'
  for (let i = 0; i < 30; i++) a.log({ kind: 'system', source: 'x', name: '-', detail: 'd' + i, ok: true })
  a.save()
  await wait(120)
  assert.equal(s.sets() - before, 1)
  assert.equal(a.status().pending, false)
  assert.ok(a.status().lastSavedAt)
  assert.ok(![...s.kv.values()].some((v) => String(v).includes('EAAB-bi-mat')), 'token không được lộ trên dịch vụ ngoài')

  const b = mk(s); await b.init()
  assert.equal(b.get().settings.accessToken, 'EAAB-bi-mat')
  assert.equal(b.get().logs.length, 30)
  assert.ok(s.kv.get('fbads:data:backup'), 'có bản sao đầu phiên')
  s.close()
})

test('flush() ghi nốt thay đổi đang chờ (dùng khi tắt máy chủ)', async () => {
  const s = await fake()
  const a = mk(s, { opts: { debounceMs: 60000 } }); await a.init()
  a.get().settings.telegramChatId = '123'; a.save()
  assert.equal(a.status().pending, true)
  await a.flush()
  assert.equal(a.status().pending, false)
  const b = mk(s); await b.init()
  assert.equal(b.get().settings.telegramChatId, '123')
  s.close()
})

test('không thay đổi gì thì không tốn lệnh ghi', async () => {
  const s = await fake()
  const a = mk(s); await a.init()
  const n = s.sets()
  a.save(); a.save()
  await wait(100)
  assert.equal(s.sets(), n)
  s.close()
})

test('sai DATA_KEY: init() từ chối và KHÔNG ghi gì lên Upstash', async () => {
  const s = await fake()
  const a = mk(s); await a.init(); a.get().rules.push({ id: 'r1' }); await (a.save(), a.flush())
  const snapshot = new Map(s.kv), n = s.calls.length
  const b = mk(s, { key: 'khoa-sai-hoan-toan-9999' })
  await assert.rejects(b.init(), /DATA_KEY sai/)
  assert.equal(s.calls.filter((c) => c[0] === 'SET').length, s.calls.slice(0, n).filter((c) => c[0] === 'SET').length)
  assert.deepEqual([...s.kv], [...snapshot])
  assert.throws(() => b.save(), /init\(\)/) // chưa nạp thì không được lưu
  s.close()
})

test('Upstash không đọc được lúc khởi động: dừng lại, không ghi dữ liệu trống đè lên', async () => {
  const s = await fake()
  const a = mk(s); await a.init(); a.get().rules.push({ id: 'r1' }); a.save(); await a.flush()
  const snapshot = new Map(s.kv)
  s.down = true
  const b = mk(s)
  await assert.rejects(b.init(), /Không đọc được dữ liệu từ Upstash/)
  s.down = false
  assert.deepEqual([...s.kv], [...snapshot])
  const c = mk(s); await c.init() // hết lỗi thì đọc lại bình thường
  assert.equal(c.get().rules[0].id, 'r1')
  s.close()
})

test('token Upstash sai: báo lỗi, không lộ token trong thông báo', async () => {
  const s = await fake()
  const st = create({ env: { ...envFor(s), UPSTASH_REDIS_REST_TOKEN: 'token-sai-nhung-dai' }, startRetryMs: 1 })
  await assert.rejects(st.init(), (e) => /401/.test(e.message) && !e.message.includes('token-sai-nhung-dai'))
  s.close()
})

test('ghi lỗi giữa chừng: giữ thay đổi và tự thử lại tới khi thành công', async () => {
  const s = await fake()
  const a = mk(s); await a.init()
  s.failNext = 2
  a.get().settings.reportTime = '09:30'; a.save()
  await wait(60)
  assert.ok(a.status().lastError, 'phải báo lỗi để giao diện hiển thị')
  assert.equal(a.status().pending, true)
  await wait(300)
  assert.equal(a.status().lastError, '')
  assert.equal(a.status().pending, false)
  const b = mk(s); await b.init()
  assert.equal(b.get().settings.reportTime, '09:30')
  s.close()
})

// ----- Không có Upstash -----
test('không cấu hình Upstash (và không phải chế độ bộ nhớ) → init() từ chối, không ghi file', async () => {
  const st = create({ env: {} })
  await assert.rejects(st.init(), /Chưa cấu hình Upstash/)
})

test('chế độ bộ nhớ (kiểm thử): dùng được ngay, không lưu đi đâu', async () => {
  const st = create({ env: { STORE_MEMORY: '1' } })
  await st.init()
  st.get().rules.push({ id: 'r1' }); st.save()
  assert.equal(st.status().mode, 'memory')
  assert.equal(st.get().rules.length, 1)
})

test('dữ liệu cũ chỉ có 1 tài khoản quảng cáo → tự chuyển sang danh sách nhiều tài khoản', async () => {
  const s = await fake()
  s.kv.set('fbads:data', remote.encode(JSON.stringify({ settings: { adAccountId: '9876543', mock: false } }), 'khoa-bi-mat-rat-dai-123'))
  const st = mk(s)
  await st.init()
  s.close()
  assert.deepEqual(st.get().settings.adAccountIds, ['9876543'])
  assert.equal(st.get().settings.adAccountId, '9876543')
})

test('báo cáo công ty mốc 9h tạo theo cách tính ngày cũ mà chưa gửi thì bị bỏ khi mở dữ liệu', async () => {
  const s = await fake()
  const companyReports = [
    { id: 'a', slot: 9, date: '2026-10-04', status: 'exists' },
    { id: 'b', slot: 9, date: '2026-10-04', status: 'sent' },
    { id: 'c', slot: 17, date: '2026-10-04', status: 'pending' },
    { id: 'd', slot: 9, date: '2026-10-05', status: 'failed', dateRule: 2 }, // ghi nhầm ngày nộp (công ty từ chối)
    { id: 'e', slot: 9, date: '2026-10-04', status: 'pending', dateRule: 3 },
  ]
  s.kv.set('fbads:data', remote.encode(JSON.stringify({ settings: {}, rules: [], schedules: [], logs: [], state: {}, companyReports }), 'khoa-bi-mat-rat-dai-123'))
  const st = mk(s); await st.init()
  assert.deepEqual(st.get().companyReports.map((r) => r.id), ['b', 'c', 'e'])
  s.close()
})
