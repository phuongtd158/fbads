// /api/insights chạy trên server thật (chế độ dùng thử, thư mục dữ liệu tạm): tham số hợp lệ trả số liệu, tham số sai bị từ chối 400.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-api-test-'))
const PORT = 10071 + Math.floor(Math.random() * 500)
const BASE = `http://127.0.0.1:${PORT}`
let proc

before(async () => {
  const env = { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PORT: String(PORT), HOST: '127.0.0.1', DATA_DIR: dir }
  proc = spawn(process.execPath, ['server.js'], { cwd: path.resolve(import.meta.dirname, '..'), env })
  let out = ''
  proc.stdout.on('data', (d) => (out += d)); proc.stderr.on('data', (d) => (out += d))
  for (let i = 0; i < 60; i++) { // chờ server lên (tối đa ~9 giây)
    try { await fetch(`${BASE}/api/auth`); return } catch { await new Promise((r) => setTimeout(r, 150)) }
  }
  throw new Error('Server không khởi động được: ' + out)
})
after(() => { if (proc) proc.kill(); fs.rmSync(dir, { recursive: true, force: true }) })

const get = async (q) => { const r = await fetch(`${BASE}/api/insights?${q}`); return { status: r.status, body: await r.json() } }

test('không có tham số = hôm nay; trả số liệu cho từng camp', async () => {
  const { status, body } = await get('')
  assert.equal(status, 200)
  assert.deepEqual(body.range, { preset: 'today' })
  assert.equal(body.days, 1)
  assert.ok(Object.keys(body.metrics).length > 0)
})

test('khoảng có sẵn: số ngày và số liệu tăng theo số ngày', async () => {
  const one = await get('range=yesterday'), seven = await get('range=last_7d'), max = await get('range=maximum')
  assert.equal(seven.status, 200)
  assert.equal(seven.body.days, 7)
  assert.equal(seven.body.key, 'p:last_7d')
  const id = Object.keys(seven.body.metrics)[0]
  assert.equal(seven.body.metrics[id].spend, one.body.metrics[id].spend * 7)
  assert.equal(max.body.since, null) // "Tối đa" không có ngày bắt đầu
  assert.equal(max.body.days, null)
  assert.ok(Object.keys(max.body.metrics).length > 0)
})

test('khoảng tuỳ chọn: tính đúng số ngày (gồm cả 2 đầu)', async () => {
  const { status, body } = await get('since=2026-01-01&until=2026-01-10')
  assert.equal(status, 200)
  assert.equal(body.days, 10)
  assert.deepEqual(body.range, { since: '2026-01-01', until: '2026-01-10' })
})

test('tham số sai bị từ chối 400 kèm lời giải thích, không phải lỗi 500', async () => {
  const cases = [
    ['range=last_9d', /không được hỗ trợ/],
    ['range=' + encodeURIComponent('../../etc/passwd'), /không được hỗ trợ/],
    ['since=2026-01-20&until=2026-01-01', /trước hoặc bằng/],
    ['since=2026-01-01&until=2999-01-01', /tương lai/],
    ['since=2015-01-01&until=2015-02-01', /37 tháng/],
    ['since=2026-9-1&until=2026-9-2', /năm-tháng-ngày/],
    ['since=2026-01-01', /năm-tháng-ngày/],
  ]
  for (const [q, re] of cases) {
    const { status, body } = await get(q)
    assert.equal(status, 400, q)
    assert.match(body.error, re, q)
  }
})

// ----- Xu hướng theo ngày của một camp (/api/objects/:id/trend) -----
const trend = async (id, q = '') => { const r = await fetch(`${BASE}/api/objects/${id}/trend${q}`); return { status: r.status, body: await r.json() } }

test('xu hướng: 30 ngày tính tới hôm nay, mỗi ngày một dòng, kèm lần tắt camp làm bằng tay', async () => {
  const r0 = await fetch(`${BASE}/api/objects/mock_1/status`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ on: false, name: 'Camp 1' }) })
  assert.equal(r0.status, 200)
  const { status, body } = await trend('mock_1')
  assert.equal(status, 200)
  assert.equal(body.days.length, 30)
  assert.equal(body.days[0].date, body.since)
  assert.equal(body.days.at(-1).date, body.until)
  assert.ok(body.days.every((d) => typeof d.spend === 'number' && d.spend > 0))
  assert.equal(body.events.length, 1)
  assert.equal(body.events[0].type, 'off')
  assert.equal(body.events[0].date, body.until)
  const again = await trend('mock_1')
  assert.deepEqual(again.body.days, body.days, 'mở lại thấy cùng số liệu')
  assert.equal((await trend('mock_2', '?days=7')).body.days.length, 7)
})

test('xu hướng: số ngày sai hoặc camp không có thì báo lỗi rõ ràng', async () => {
  assert.equal((await trend('mock_1', '?days=3')).status, 400)
  assert.equal((await trend('mock_1', '?days=abc')).status, 400)
  const nf = await trend('khong_co')
  assert.equal(nf.status, 404)
  assert.match(nf.body.error, /Không tìm thấy/)
})
