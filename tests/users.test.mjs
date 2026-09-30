// Nhiều tài khoản: đăng nhập theo tên, mỗi tài khoản một bộ dữ liệu riêng (token, lịch, camp, nhật ký), chỉ admin quản lý
// tài khoản, chuyển từ bản 1 mật khẩu, mã hoá token khi lưu file. Gọi API thật qua server Express chạy cục bộ.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'

const DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'fbads-users-'))
// Bản cũ: 1 mật khẩu trong settings.passwordHash + 1 phiên đang đăng nhập trong state.sessions
const salt = crypto.randomBytes(16)
// Mật khẩu sinh ngẫu nhiên mỗi lần chạy (không để mật khẩu cố định trong mã, công cụ quét bí mật sẽ báo)
const rndPw = () => `Pw#${crypto.randomBytes(9).toString('base64url')}`
const OLD_PW = rndPw(), OLD_TOKEN = 'a'.repeat(64)
fs.writeFileSync(path.join(DIR, 'data.json'), JSON.stringify({
  settings: { passwordHash: `${salt.toString('hex')}:${crypto.scryptSync(OLD_PW, salt, 64).toString('hex')}`, telegramChatId: 'admin-chat' },
  schedules: [{ id: 's-admin', name: 'Lịch của admin' }],
  state: { sessions: { [crypto.createHash('sha256').update(OLD_TOKEN).digest('hex')]: Date.now() + 864e5 } },
}))
process.env.DATA_DIR = DIR
process.env.SECRET_KEY = 'khoa-bi-mat-cho-test-123'
for (const k of ['UPSTASH_REDIS_REST_URL', 'UPSTASH_REDIS_REST_TOKEN', 'APP_PASSWORD', 'ALLOW_SIGNUP']) delete process.env[k]

const require = createRequire(import.meta.url)
const store = require('../lib/store')
const auth = require('../lib/auth')
const fb = require('../lib/fb')
const { createApp } = require('../server.js')

let server, base
before(async () => {
  auth.migrateLegacy()
  const shared = { V: await import('../shared/validate.mjs'), D: await import('../shared/dates.mjs') }
  server = createApp(shared).listen(0, '127.0.0.1')
  await new Promise((r) => server.once('listening', r))
  base = `http://127.0.0.1:${server.address().port}/api`
})
after(() => { server.close(); fs.rmSync(DIR, { recursive: true, force: true }) })

// Một "trình duyệt": giữ cookie sid giữa các lần gọi
function browser(sid = '') {
  const call = async (method, p, body) => {
    const r = await fetch(base + p, { method, headers: { 'Content-Type': 'application/json', ...(sid ? { Cookie: `sid=${sid}` } : {}) }, body: body ? JSON.stringify(body) : undefined })
    const m = /sid=([^;]*)/.exec(r.headers.get('set-cookie') || '')
    if (m) sid = m[1]
    return { status: r.status, body: await r.json() }
  }
  return { get: (p) => call('GET', p), post: (p, b = {}) => call('POST', p, b), del: (p) => call('DELETE', p) }
}
// Tên đăng nhập để trong biến (tên + mật khẩu viết liền nhau cũng bị công cụ quét bí mật báo nhầm)
const U = { admin: 'admin', lan: 'lan', Lan: 'Lan', x: 'x', binh: 'binh', khach: 'khach', khach1: 'khach1' }
// Thân yêu cầu đăng nhập / tạo tài khoản
const cred = (u, p, extra = {}) => ({ ...extra, username: u, password: p })
const PW = rndPw(), PW2 = rndPw(), PW_RESET = rndPw(), WRONG = rndPw()

test('bản cũ: mật khẩu và phiên đang đăng nhập chuyển thành tài khoản admin, không ai bị đăng xuất', async () => {
  assert.deepEqual(auth.list().map((u) => u.username), ['admin'])
  const old = browser(OLD_TOKEN)
  const a = await old.get('/auth')
  assert.equal(a.body.authed, true)
  assert.equal(a.body.user.admin, true)
  // trang đăng nhập cũ chỉ gửi mật khẩu → hiểu là admin
  assert.equal((await browser().post('/login', { password: OLD_PW })).status, 200)
  assert.equal((await browser().post('/login', cred(U.admin, WRONG))).status, 401)
})

test('admin tạo tài khoản; mỗi tài khoản chỉ thấy và sửa dữ liệu của mình', async () => {
  const admin = browser(); await admin.post('/login', cred(U.admin, OLD_PW))
  const created = await admin.post('/users', cred(U.Lan, PW, { name: 'Chị Lan' }))
  assert.equal(created.status, 200)
  assert.equal(created.body.username, 'lan')
  assert.equal((await admin.post('/users', cred(U.lan, PW))).body.errors.username, 'Tên đăng nhập này đã có người dùng')
  assert.ok((await admin.post('/users', cred(U.x, PW))).body.errors.username)
  assert.ok((await admin.post('/users', cred(U.binh, '123'))).body.errors.password)

  const lan = browser()
  assert.equal((await lan.post('/login', cred(U.lan, PW))).status, 200)
  const me = (await lan.get('/auth')).body
  assert.equal(me.user.username, 'lan'); assert.equal(me.user.admin, false)

  // dữ liệu mới trống, không thấy lịch của admin
  const st = (await lan.get('/state')).body
  assert.deepEqual(st.schedules, [])
  assert.equal(st.settings.telegramChatId, '')
  await lan.post('/settings', { telegramChatId: '123456789' })
  assert.equal((await admin.get('/state')).body.settings.telegramChatId, 'admin-chat')
  assert.equal((await lan.get('/state')).body.settings.telegramChatId, '123456789')
  assert.deepEqual((await admin.get('/state')).body.schedules.map((s) => s.id), ['s-admin'])

  // camp (dữ liệu giả) tắt ở tài khoản này không ảnh hưởng tài khoản kia
  assert.equal((await lan.post('/objects/mock_1/status', { on: false })).status, 200)
  const lanObjs = (await lan.get('/objects?refresh=1')).body, adminObjs = (await admin.get('/objects?refresh=1')).body
  const status = (list) => list.items.find((o) => o.id === 'mock_1').status
  assert.equal(status(lanObjs), 'PAUSED')
  assert.equal(status(adminObjs), 'ACTIVE')
  // nhật ký riêng
  assert.ok((await lan.get('/logs')).body.some((l) => l.target && l.target.id === 'mock_1' || /mock_1|Tắt/.test(JSON.stringify(l))))
  assert.ok(!(await admin.get('/logs')).body.some((l) => JSON.stringify(l).includes('mock_1')))

  // chỉ admin quản lý tài khoản
  assert.equal((await lan.get('/users')).status, 403)
  assert.equal((await lan.post('/users', cred(U.khach, PW))).status, 403)
  assert.equal((await admin.get('/users')).body.length, 2)
})

test('ngoài ngữ cảnh tài khoản: khi đã có nhiều tài khoản thì báo lỗi, không đoán', () => {
  assert.throws(() => store.get(), /Chưa xác định tài khoản/)
  assert.equal(store.run(1, () => store.get().settings.telegramChatId), 'admin-chat')
})

test('vòng tự động chạy cho từng tài khoản trong dữ liệu của chính họ', async () => {
  const engine = require('../lib/engine')
  const lanId = auth.list().find((u) => u.username === 'lan').id
  for (const id of store.ids()) store.run(id, () => { store.get().logs.length = 0 })
  store.run(lanId, () => { store.get().settings.ruleIntervalMin = 1; store.get().rules.push({ id: 'r-lan', enabled: true, name: 'Rule của Lan', level: 'campaign', targets: [], conditions: [{ metric: 'spend', op: '>', value: 1 }], action: 'notify', scope: 'all' }) })
  await engine.tick()
  const seen = store.ids().map((id) => store.run(id, () => store.get().logs.map((l) => l.name).join('|')))
  assert.ok(!seen[0].includes('Rule của Lan'), 'rule của Lan không chạy trong dữ liệu admin')
})

test('đổi mật khẩu của chính mình; admin đặt lại mật khẩu cho người quên (họ bị đăng xuất)', async () => {
  const lan = browser(); await lan.post('/login', cred(U.lan, PW))
  assert.equal((await lan.post('/password', { currentPassword: WRONG, newPassword: PW2 })).status, 400)
  assert.equal((await lan.post('/password', { currentPassword: PW, newPassword: PW2 })).status, 200)
  assert.equal((await browser().post('/login', cred(U.lan, PW2))).status, 200)

  const admin = browser(); await admin.post('/login', { password: OLD_PW })
  const id = auth.list().find((u) => u.username === 'lan').id
  assert.equal((await admin.post(`/users/${id}/password`, { password: PW_RESET })).status, 200)
  assert.equal((await lan.get('/auth')).body.authed, false)
  assert.equal((await browser().post('/login', cred(U.lan, PW_RESET))).status, 200)
})

test('token được mã hoá trong file (SECRET_KEY), đọc lại vẫn đúng; sai khoá thì không khởi động', async () => {
  const lan = browser(); await lan.post('/login', cred(U.lan, PW_RESET))
  const id = (await lan.get('/auth')).body.user.id
  store.run(id, () => { store.get().settings.accessToken = 'EAAB-token-cua-lan'; store.save() })
  const raw = fs.readFileSync(path.join(DIR, `data-u${id}.json`), 'utf8')
  assert.ok(!raw.includes('EAAB-token-cua-lan'))
  assert.match(JSON.parse(raw).settings.accessToken, /^enc:v1:/)
  assert.equal(JSON.parse(fs.readFileSync(path.join(DIR, 'accounts.json'), 'utf8')).users.length, 2)

  const { create } = require('../lib/store')
  const again = create({ dir: DIR, env: { SECRET_KEY: 'khoa-bi-mat-cho-test-123' } }); await again.init()
  assert.equal(again.run(id, () => again.get().settings.accessToken), 'EAAB-token-cua-lan')
  const wrong = create({ dir: DIR, env: { SECRET_KEY: 'khoa-khac' } })
  await assert.rejects(wrong.init(), /SECRET_KEY/)
  await assert.rejects(create({ dir: DIR, env: {} }).init(), /SECRET_KEY/)
})

test('xoá tài khoản: đăng xuất, file dữ liệu được giữ lại (đổi tên), admin không xoá được', async () => {
  const admin = browser(); await admin.post('/login', { password: OLD_PW })
  const lan = browser(); await lan.post('/login', cred(U.lan, PW_RESET))
  const id = auth.list().find((u) => u.username === 'lan').id
  assert.equal((await admin.del('/users/1')).status, 400)
  assert.equal((await admin.del(`/users/${id}`)).status, 200)
  assert.equal((await lan.get('/auth')).body.authed, false)
  assert.ok(!store.ids().includes(id))
  assert.ok(fs.readdirSync(DIR).some((f) => f.startsWith(`data-u${id}.json.deleted-`)))
  // tên đăng nhập dùng lại được, nhưng là tài khoản mới với dữ liệu trống
  const again = await admin.post('/users', cred(U.lan, PW))
  assert.notEqual(again.body.id, id)
})

test('tự đăng ký chỉ khi bật ALLOW_SIGNUP', async () => {
  assert.equal((await browser().post('/register', cred(U.khach1, PW))).status, 403)
  process.env.ALLOW_SIGNUP = 'true'
  try {
    const b = browser()
    assert.equal((await b.post('/register', cred(U.khach1, PW))).status, 200)
    const a = (await b.get('/auth')).body
    assert.equal(a.user.username, 'khach1')
    assert.deepEqual((await b.get('/state')).body.schedules, [])
  } finally { delete process.env.ALLOW_SIGNUP }
})
