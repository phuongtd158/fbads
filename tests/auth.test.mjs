// Kiểm thử cách lấy IP người dùng sau proxy và giới hạn nhập sai mật khẩu. Dữ liệu ghi vào thư mục tạm.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-auth-'))
const require = createRequire(import.meta.url)
const auth = require('../lib/auth')

const req = (headers = {}, ip = '10.1.1.1') => ({ headers, socket: { remoteAddress: ip } })
const ENV = ['TRUST_PROXY', 'CLIENT_IP_HEADER', 'RENDER']
const saved = Object.fromEntries(ENV.map((k) => [k, process.env[k]]))
beforeEach(() => { for (const k of ENV) delete process.env[k] })
after(() => { for (const k of ENV) { if (saved[k] === undefined) delete process.env[k]; else process.env[k] = saved[k] } })

test('không có proxy: dùng IP của kết nối, bỏ qua mọi tiêu đề', () => {
  assert.equal(auth.ipOf(req({ 'x-forwarded-for': '1.1.1.1', 'fly-client-ip': '2.2.2.2', 'cf-connecting-ip': '3.3.3.3' })), '10.1.1.1')
})

test('sau proxy chung (ngrok): lấy phần tử CUỐI của X-Forwarded-For, tiêu đề fly/cf bị bỏ qua', () => {
  process.env.TRUST_PROXY = '1'
  assert.equal(auth.ipOf(req({ 'x-forwarded-for': 'gia-mao, 9.9.9.9' })), '9.9.9.9')
  assert.equal(auth.ipOf(req({ 'x-forwarded-for': '9.9.9.9', 'fly-client-ip': 'gia-mao', 'cf-connecting-ip': 'gia-mao' })), '9.9.9.9')
  assert.equal(auth.ipOf(req({})), '10.1.1.1') // không có gì → IP kết nối
})

test('Render: dùng cf-connecting-ip', () => {
  process.env.TRUST_PROXY = '1'; process.env.RENDER = 'true'
  assert.equal(auth.ipOf(req({ 'cf-connecting-ip': '5.5.5.5', 'x-forwarded-for': 'gia-mao, 172.70.1.1', 'fly-client-ip': 'gia-mao' })), '5.5.5.5')
})

test('Render tự coi là sau proxy dù không đặt TRUST_PROXY', () => {
  process.env.RENDER = 'true'
  assert.equal(auth.ipOf(req({ 'cf-connecting-ip': '5.5.5.5' })), '5.5.5.5')
})

test('tiêu đề fly-client-ip không còn được tin', () => {
  process.env.TRUST_PROXY = '1'
  assert.equal(auth.ipOf(req({ 'fly-client-ip': 'gia-mao', 'x-forwarded-for': '9.9.9.9' })), '9.9.9.9')
})

test('CLIENT_IP_HEADER tự chọn tiêu đề', () => {
  process.env.TRUST_PROXY = '1'; process.env.CLIENT_IP_HEADER = 'X-Real-IP'
  assert.equal(auth.ipOf(req({ 'x-real-ip': '6.6.6.6' })), '6.6.6.6')
})

test('nhập sai 5 lần thì khoá theo IP thật, IP khác không bị ảnh hưởng', () => {
  process.env.TRUST_PROXY = '1'; process.env.RENDER = 'true'
  const bad = req({ 'cf-connecting-ip': '7.7.7.7' })
  for (let i = 0; i < 5; i++) { assert.equal(auth.lockedMinutes(bad), 0); auth.recordFail(bad) }
  assert.ok(auth.lockedMinutes(bad) > 0)
  assert.equal(auth.lockedMinutes(req({ 'cf-connecting-ip': '8.8.8.8' })), 0)
  // đổi phần tử đầu của X-Forwarded-For không lách được vì tool không dùng nó khi đã có cf-connecting-ip
  assert.ok(auth.lockedMinutes(req({ 'cf-connecting-ip': '7.7.7.7', 'x-forwarded-for': `ngau-nhien-${Math.random()}` })) > 0)
})
