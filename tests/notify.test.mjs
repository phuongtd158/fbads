// Gửi Telegram cho nhiều người nhận: fetch được thay bằng hàm giả nên không gọi Telegram thật.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { parseChatIds, checkTelegramChats, validateSettings, MAX_TG_CHATS } from '../shared/validate.mjs'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-notify-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const notify = require('../lib/notify')

const TOKEN = '123456789:AAEhBP0av28ZzTestTokenTestTokenTest12'
const sent = [] // { chat, text, token }
let script = {} // chat_id → phản hồi giả: { status, body } hoặc 'network'
const reply = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
globalThis.fetch = async (url, init) => {
  const u = String(url), body = JSON.parse(init.body)
  sent.push({ chat: body.chat_id, text: body.text, token: (u.match(/bot([^/]+)\//) || [])[1] })
  const s = script[body.chat_id]
  if (s === 'network') throw Object.assign(new TypeError('fetch failed'), { cause: { code: 'ENOTFOUND' } })
  if (s) return reply(s.status, s.body)
  return reply(200, { ok: true, result: {} })
}
const cfg = (chatId, token = TOKEN) => Object.assign(store.get().settings, { telegramToken: token, telegramChatId: chatId })
beforeEach(() => { sent.length = 0; script = {}; cfg('') })

test('gửi cho từng Chat ID với cùng nội dung và đúng token', async () => {
  cfg('111111, -100222222')
  const r = await notify.send('Xin chào')
  assert.deepEqual(sent.map((s) => [s.chat, s.text, s.token]).sort(), [['-100222222', 'Xin chào', TOKEN], ['111111', 'Xin chào', TOKEN]])
  assert.equal(r.configured, true)
  assert.deepEqual(r.results.map((x) => [x.id, x.ok]), [['111111', true], ['-100222222', true]])
})

test('một người lỗi không làm mất tin của người còn lại; lỗi được dịch ra lời khuyên', async () => {
  cfg('111111, 222222, 333333')
  script = {
    222222: { status: 403, body: { ok: false, error_code: 403, description: 'Forbidden: bot was blocked by the user' } },
    333333: { status: 400, body: { ok: false, error_code: 400, description: 'Bad Request: chat not found' } },
  }
  const r = await notify.send('x')
  assert.equal(sent.length, 3, 'vẫn thử đủ cả 3')
  const by = Object.fromEntries(r.results.map((x) => [x.id, x]))
  assert.equal(by['111111'].ok, true)
  assert.match(by['222222'].error, /chặn bot/)
  assert.match(by['333333'].error, /chưa từng nhắn cho bot/)
  assert.equal(await notify.telegram('x'), true, 'còn ít nhất một người nhận được')
})

test('không ai nhận được → telegram() trả false', async () => {
  cfg('111111, 222222')
  script = { 111111: 'network', 222222: { status: 401, body: { ok: false, error_code: 401, description: 'Unauthorized' } } }
  const r = await notify.send('x')
  assert.deepEqual(r.results.map((x) => x.ok), [false, false])
  assert.match(r.results[0].error, /Không kết nối/)
  assert.match(r.results[1].error, /Bot Token sai/)
  assert.equal(await notify.telegram('x'), false)
})

test('kết quả và nhật ký không lộ Bot Token', async () => {
  cfg('111111, 222222')
  script = { 111111: 'network', 222222: { status: 403, body: { ok: false, error_code: 403, description: `Forbidden: ${TOKEN}` } } }
  const r = await notify.send('x')
  assert.ok(!JSON.stringify(r).includes('AAEhBP0av28Z'), 'token không được nằm trong kết quả trả về UI')
  assert.ok(!JSON.stringify(notify.reply(r)).includes('AAEhBP0av28Z'))
})

test('chưa cài Token hoặc Chat ID thì không gửi gì', async () => {
  cfg('111111', '')
  assert.deepEqual(await notify.send('x'), { configured: false, results: [] })
  cfg('', TOKEN)
  assert.deepEqual(await notify.send('x'), { configured: false, results: [] })
  cfg('  , ;  ', TOKEN)
  assert.equal((await notify.send('x')).configured, false)
  assert.equal(sent.length, 0)
  assert.equal(await notify.telegram('x'), false)
})

test('dấu ngăn cách lộn xộn và ID trùng chỉ gửi một lần', async () => {
  cfg('111111,111111 ; 222222\n333333,  @Kenh_Abc, @kenh_abc')
  await notify.send('x')
  assert.deepEqual(sent.map((s) => s.chat).sort(), ['111111', '222222', '333333', '@Kenh_Abc'])
})

test('dữ liệu cũ (một Chat ID) vẫn chạy như trước', async () => {
  cfg('123456789')
  const r = await notify.send('x')
  assert.deepEqual(r.results, [{ id: '123456789', ok: true }])
})

test('phản hồi cho giao diện: một phần thành công là 200 kèm chi tiết, không ai nhận được là 400', () => {
  const part = notify.reply({ configured: true, results: [{ id: '1', ok: true }, { id: '2', ok: false, error: 'Người này đã chặn bot.' }] })
  assert.equal(part.status, 200)
  assert.deepEqual([part.body.sent, part.body.total], [1, 2])
  assert.equal(part.body.results[1].error, 'Người này đã chặn bot.')
  const none = notify.reply({ configured: true, results: [{ id: '1', ok: false, error: 'A' }, { id: '2', ok: false, error: 'B' }] })
  assert.equal(none.status, 400)
  assert.match(none.body.error, /Không gửi được cho ai cả.*A/)
  assert.equal(none.body.results.length, 2)
  const one = notify.reply({ configured: true, results: [{ id: '1', ok: false, error: 'A' }] })
  assert.match(one.body.error, /Gửi thất bại: A/)
  const no = notify.reply({ configured: false, results: [] })
  assert.equal(no.status, 400)
  assert.match(no.body.error, /Bot Token và Chat ID/)
})

test('dịch lỗi Telegram', () => {
  assert.match(notify.friendlyTg(403, 'Forbidden: bot is not a member of the group chat'), /chưa ở trong nhóm/)
  assert.match(notify.friendlyTg(403, 'Forbidden: bot can\'t initiate conversation with a user'), /chưa từng nhắn cho bot/)
  assert.match(notify.friendlyTg(429, 'Too Many Requests: retry after 5'), /giới hạn tốc độ/)
  assert.match(notify.friendlyTg(500, 'Internal'), /Telegram báo: Internal/) // lỗi lạ: giữ nguyên mô tả
  assert.ok(notify.friendlyTg(undefined, ''))
})

// ----- Kiểm tra dữ liệu (dùng chung với giao diện) -----
test('tách và kiểm tra danh sách Chat ID', () => {
  assert.deepEqual(parseChatIds('111111, 222222;333333\n444444  555555'), ['111111', '222222', '333333', '444444', '555555'])
  assert.deepEqual(parseChatIds(' , ; '), [])
  assert.deepEqual(parseChatIds(null), [])
  assert.deepEqual(parseChatIds('@Kenh_Abc, @kenh_abc'), ['@Kenh_Abc'])
  assert.equal(checkTelegramChats(''), '')
  assert.equal(checkTelegramChats('111111, -100222222, @kenh_abc'), '')
  assert.match(checkTelegramChats('111111, abc'), /“abc”/)
  assert.match(checkTelegramChats('1234'), /không phải Chat ID/) // quá ngắn
  assert.match(checkTelegramChats(Array.from({ length: MAX_TG_CHATS + 1 }, (_, i) => String(100000 + i)).join(',')), /Tối đa 10/)
  assert.equal(checkTelegramChats(Array.from({ length: MAX_TG_CHATS }, (_, i) => String(100000 + i)).join(',')), '')
})

test('lưu cài đặt: chuẩn hoá thành "id1, id2", báo lỗi khi có ID sai', () => {
  assert.equal(validateSettings({ telegramChatId: ' 111111 ,222222;111111 ' }).value.telegramChatId, '111111, 222222')
  assert.equal(validateSettings({ telegramChatId: '' }).value.telegramChatId, '')
  assert.equal(validateSettings({ telegramChatId: '123456789' }).value.telegramChatId, '123456789')
  const bad = validateSettings({ telegramChatId: '111111, xyz' })
  assert.match(bad.errors.telegramChatId, /“xyz”/)
})

test('hai bản tách Chat ID (server và giao diện) luôn cho cùng kết quả', () => {
  for (const s of ['1', '111111,222222', '  a , b;c\nd  e ', '@A_bcde,@a_bcde', '', ',,,', '111111 111111']) {
    assert.deepEqual(notify.chatIdsOf(s), parseChatIds(s), JSON.stringify(s))
  }
})
