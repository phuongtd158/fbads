import { reactive } from 'vue'
import { api, onUnauthorized } from '../lib/api'

export const state = reactive({
  ready: false,
  auth: { required: false, authed: true, envManaged: false },
  settings: {},
  schedules: [],
  rules: [],
  objs: [],
  objsLoaded: false,
  objsLoading: false,
  objsErr: '',
  objsAt: null,
  conn: null,
  connChecking: false,
})

onUnauthorized(() => { state.auth.authed = false })

export async function loadAuth() {
  try { Object.assign(state.auth, await api('auth', 'GET', undefined, { bg: true })) } catch { /* tool tắt */ }
}

export async function loadState() {
  const s = await api('state')
  state.settings = s.settings
  state.schedules = s.schedules
  state.rules = s.rules
}

export async function loadObjs(force = false, bg = false) {
  state.objsLoading = true
  try {
    state.objs = await api('objects' + (force ? '?refresh=1' : ''), 'GET', undefined, { bg })
    state.objsErr = ''
    state.objsAt = new Date()
  } catch (e) {
    if (!e.silent) { if (!bg) state.objs = []; state.objsErr = e.message }
  } finally {
    state.objsLoading = false
    state.objsLoaded = true
  }
}

export async function ensureObjs() { if (!state.objsLoaded) await loadObjs() }

export async function checkConn(force = false) {
  if (state.settings.mock) { state.conn = null; return }
  if (!force && state.conn && Date.now() - state.conn.at < 300000) return
  state.connChecking = true
  try {
    state.conn = { ...(await api('connection', 'GET', undefined, { bg: true })), at: Date.now() }
  } catch (e) {
    state.conn = { ok: false, error: e.message, at: Date.now() }
  } finally { state.connChecking = false }
}

export function resetData() {
  state.objs = []; state.objsLoaded = false; state.objsErr = ''; state.conn = null
}

export async function bootstrap() {
  await loadAuth()
  if (!(state.auth.required && !state.auth.authed)) {
    try { await loadState() } catch { /* hiển thị lỗi qua toast ở nơi gọi */ }
  }
  state.ready = true
  if (state.auth.authed) checkConn()
}

export async function afterLogin() {
  // Tải dữ liệu trước, bật giao diện chính sau cùng (loadAuth đặt authed=true) để không bị resetData xoá dở
  await loadState()
  resetData()
  await loadAuth()
  checkConn()
}

export async function logout() {
  await api('logout', 'POST')
  state.auth.authed = false
  state.settings = {}; state.schedules = []; state.rules = []
  resetData()
}

export async function setObjStatus(o, on) {
  await api(`objects/${o.id}/status`, 'POST', { on, name: o.name })
  o.status = on ? 'ACTIVE' : 'PAUSED'
  if (['ACTIVE', 'PAUSED'].includes(o.effective)) o.effective = o.status
}

export async function setObjBudget(o, amount) {
  await api(`objects/${o.id}/budget`, 'POST', { amount, name: o.name })
  o.dailyBudget = amount
}

export const modeOf = (s) => (s.mock ? 'mock' : s.dryRun ? 'dry' : 'live')

// Lưu một phần cài đặt; reset=true khi thay đổi làm đổi nguồn dữ liệu (chế độ, tài khoản, loại kết quả)
export async function saveSettings(patch, { reset = false } = {}) {
  await api('settings', 'POST', patch)
  await loadState()
  if (reset) { resetData(); checkConn(true) }
}
