// Cài tool như app (PWA): file app, icon, service worker và trang offline phải mở được khi CHƯA đăng nhập
// (trình duyệt tải chúng trước khi có cookie), còn API vẫn cần đăng nhập. Chạy trên server thật, bản build trong public/.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-pwa-test-'))
const PORT = 11100 + Math.floor(Math.random() * 400)
const BASE = `http://127.0.0.1:${PORT}`
let proc

before(async () => {
  const env = { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PORT: String(PORT), HOST: '127.0.0.1', DATA_DIR: dir, APP_PASSWORD: crypto.randomBytes(12).toString('base64url') } // bật đăng nhập; mật khẩu ngẫu nhiên
  proc = spawn(process.execPath, ['server.js'], { cwd: path.resolve(import.meta.dirname, '..'), env })
  let out = ''
  proc.stdout.on('data', (d) => (out += d)); proc.stderr.on('data', (d) => (out += d))
  for (let i = 0; i < 60; i++) {
    try { await fetch(`${BASE}/api/auth`); return } catch { await new Promise((r) => setTimeout(r, 150)) }
  }
  throw new Error('Server không khởi động được: ' + out)
})
after(() => { if (proc) proc.kill(); fs.rmSync(dir, { recursive: true, force: true }) })

test('manifest mở được khi chưa đăng nhập, có tên, chạy toàn màn hình và đủ icon', async () => {
  const r = await fetch(`${BASE}/manifest.webmanifest`)
  assert.equal(r.status, 200)
  assert.match(r.headers.get('content-type'), /manifest\+json/)
  const m = await r.json()
  assert.equal(m.name, 'FB Ads Auto')
  assert.equal(m.display, 'standalone')
  assert.equal(m.start_url, '/')
  const sizes = m.icons.map((i) => i.sizes)
  assert.ok(sizes.includes('192x192') && sizes.includes('512x512'))
  assert.ok(m.icons.some((i) => i.purpose === 'maskable'))
  for (const i of [...m.icons, { src: '/icons/apple-touch-icon.png' }]) {
    const ir = await fetch(BASE + i.src)
    assert.equal(ir.status, 200, i.src)
    assert.equal(ir.headers.get('content-type'), 'image/png')
  }
})

test('service worker và trang offline mở được khi chưa đăng nhập; service worker không bị cache lâu', async () => {
  const sw = await fetch(`${BASE}/sw.js`)
  assert.equal(sw.status, 200)
  assert.match(sw.headers.get('content-type'), /javascript/)
  assert.equal(sw.headers.get('cache-control'), 'no-cache', 'bản sửa service worker phải tới máy ngay sau deploy')
  const off = await fetch(`${BASE}/offline.html`)
  assert.equal(off.status, 200)
  assert.match(await off.text(), /Không có kết nối mạng/)
})

test('trang chính khai báo manifest và icon iPhone; API vẫn cần đăng nhập', async () => {
  const html = await (await fetch(`${BASE}/`)).text()
  assert.match(html, /<link rel="manifest" href="\/manifest\.webmanifest"/)
  assert.match(html, /apple-touch-icon/)
  assert.equal((await fetch(`${BASE}/api/state`)).status, 401)
})
