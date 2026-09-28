// Điều khiển tool qua Telegram (lib/tgbot.js) trên dữ liệu giả. Telegram được thay bằng hàm giả ghi lại mọi lời gọi.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-tgbot-test-'))
const require = createRequire(import.meta.url)
const store = require('../lib/store')
const fb = require('../lib/fb')
const engine = require('../lib/engine')
const bot = require('../lib/tgbot')

let calls, updates
globalThis.fetch = async (url, init = {}) => {
  const method = String(url).split('/').pop()
  const body = JSON.parse(init.body || '{}')
  calls.push({ method, body })
  const ok = (result) => new Response(JSON.stringify({ ok: true, result }), { headers: { 'content-type': 'application/json' } })
  if (method === 'getUpdates') return ok(updates.splice(0))
  return ok({ message_id: 900 })
}
const sent = (m = 'sendMessage') => calls.filter((c) => c.method === m).map((c) => c.body)
const nowS = () => Math.floor(Date.now() / 1000)
const msg = (text, chat = 42) => ({ update_id: 1, message: { message_id: 1, date: nowS(), chat: { id: chat }, text } })
const press = (data, chat = 42, date = nowS()) => ({ update_id: 2, callback_query: { id: 'q1', data, message: { message_id: 77, date, chat: { id: chat } } } })

beforeEach(() => {
  const d = store.get()
  d.logs.length = 0; d.rules.length = 0
  d.state = { fired: {}, lastRule: {}, lastReport: '', budgetDay: null, killFired: '', hold: {}, resume: {} }
  Object.assign(d.settings, { mock: true, dryRun: true, telegramToken: '123:abc', telegramChatId: '42, -100555', telegramCommands: true })
  fb.resetMock(); fb.resetCache(); bot.reset()
  calls = []; updates = []
})

test('/status và /camps trả lời người đã cài; /camps có nút tắt từng camp', async () => {
  await bot.handleUpdate(msg('/status'))
  assert.match(sent()[0].text, /Trạng thái tool/)
  assert.match(sent()[0].text, /Dùng thử/)
  assert.match(sent()[0].text, /Tài khoản mẫu A/)
  await bot.handleUpdate(msg('/camps@FbAdsBot', -100555)) // lệnh trong nhóm có kèm tên bot
  const camps = sent()[1]
  assert.match(camps.text, /camp đang chạy/)
  assert.ok(camps.reply_markup.inline_keyboard.length > 0)
  assert.match(camps.reply_markup.inline_keyboard[0][0].callback_data, /^o:mock_/)
})

test('người lạ: /start chỉ được biết Chat ID của mình, lệnh khác bị bỏ qua', async () => {
  await bot.handleUpdate(msg('/start', 999))
  assert.match(sent()[0].text, /Chat ID của bạn là <code>999<\/code>/)
  await bot.handleUpdate(msg('/status', 999))
  await bot.handleUpdate(msg('/camps', 999))
  assert.equal(sent().length, 1)
  await bot.handleUpdate(press('yo:mock_1', 999))
  assert.equal(sent('editMessageText').length, 0, 'người lạ bấm nút cũng không làm gì')
  assert.equal((await fb.listObjects(true)).find((o) => o.id === 'mock_1').status, 'ACTIVE')
})

test('bấm Tắt: hỏi lại trước, bấm Có mới tắt và ghi nhật ký nguồn Telegram', async () => {
  await bot.handleUpdate(press('o:mock_1'))
  const ask = sent()[0]
  assert.match(ask.text, /^Tắt <b>/)
  assert.deepEqual(ask.reply_markup.inline_keyboard[0].map((b) => b.callback_data), ['yo:mock_1', 'x'])
  assert.equal((await fb.listObjects(true)).find((o) => o.id === 'mock_1').status, 'ACTIVE', 'chưa xác nhận thì chưa tắt')

  await bot.handleUpdate(press('yo:mock_1'))
  assert.equal((await fb.listObjects(true)).find((o) => o.id === 'mock_1').status, 'PAUSED')
  assert.match(sent('editMessageText')[0].text, /Đã tắt/)
  const l = store.get().logs[0]
  assert.equal(l.source, 'Telegram')
  assert.equal(l.after.status, 'PAUSED')
})

test('nút xác nhận quá 10 phút thì hết hạn, không làm gì', async () => {
  await bot.handleUpdate(press('yo:mock_1', 42, nowS() - bot.CONFIRM_TTL_S - 5))
  assert.match(sent('editMessageText')[0].text, /hết hạn/)
  assert.equal((await fb.listObjects(true)).find((o) => o.id === 'mock_1').status, 'ACTIVE')
})

test('Huỷ thì sửa tin thành "Đã huỷ"', async () => {
  await bot.handleUpdate(press('x'))
  assert.equal(sent('editMessageText')[0].text, 'Đã huỷ.')
})

test('tin báo của rule có nút Hoàn tác; bấm Có thì hoàn tác được', async () => {
  const objs = await fb.listObjects(true)
  const o = objs.find((x) => x.id === 'mock_2')
  await engine.act(o, { type: 'off' }, 'Rule: thử', { kind: 'rule', refId: 'r1', refName: 'thử' })
  const note = sent()[0]
  assert.equal(note.reply_markup.inline_keyboard[0][0].text, '↩️ Hoàn tác')
  const data = note.reply_markup.inline_keyboard[0][0].callback_data
  assert.match(data, /^u:/)

  calls = []
  await bot.handleUpdate(press(data))
  assert.match(sent()[0].text, /Hoàn tác thay đổi này/)
  await bot.handleUpdate(press(`y${data}`))
  assert.match(sent('editMessageText')[0].text, /Hoàn tác: bật lại camp/)
  assert.equal((await fb.listObjects(true)).find((x) => x.id === 'mock_2').status, 'ACTIVE')
})

test('tắt "Nhận lệnh từ Telegram" thì tin báo không kèm nút', async () => {
  store.get().settings.telegramCommands = false
  const o = (await fb.listObjects(true)).find((x) => x.id === 'mock_2')
  await engine.act(o, { type: 'off' }, 'Rule: thử', { kind: 'rule', refId: 'r1', refName: 'thử' })
  assert.equal(sent()[0].reply_markup, undefined)
})

test('vừa khởi động: bỏ tin nhắn cũ hơn 5 phút, chỉ chạy lệnh mới', async () => {
  updates = [{ update_id: 10, message: { message_id: 1, date: nowS() - 3600, chat: { id: 42 }, text: '/report' } }, { ...msg('/status'), update_id: 11 }]
  await bot.pollOnce()
  const texts = sent().map((b) => b.text)
  assert.equal(texts.length, 1)
  assert.match(texts[0], /Trạng thái tool/)
  assert.equal(calls.filter((c) => c.method === 'getUpdates')[0].body.offset, 0)
  updates = []
  await bot.pollOnce()
  assert.equal(calls.filter((c) => c.method === 'getUpdates')[1].body.offset, 12, 'lần sau hỏi tiếp từ tin kế tiếp')
})
