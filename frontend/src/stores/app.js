import { reactive } from 'vue'
import { api, onUnauthorized } from '../lib/api'

export const state = reactive({
  ready: false,
  // required: đã có tài khoản (phải đăng nhập); setup: chưa có tài khoản nào (chế độ mở); signup: được tự đăng ký
  // workspace: { id, name, role } đang chọn; workspaces: mọi workspace của người này
  auth: { required: false, authed: true, envManaged: false, setup: false, signup: false, user: null, workspace: null, workspaces: [] },
  settings: {},
  schedules: [],
  rules: [],
  objs: [],
  objsLoaded: false,
  objsLoading: false,
  objsErr: '',
  objsAt: null,
  objsMeta: null, // { stale, blockedUntil, usage: { pct, tier } } — số liệu cũ vì Facebook giới hạn số lần gọi, mức dùng API
  conn: null,
  connChecking: false,
  storage: null, // { mode: 'file' | 'remote', provider, lastSavedAt, lastError, pending }
})

onUnauthorized(() => { state.auth.authed = false })

export async function loadAuth() {
  try { Object.assign(state.auth, await api('auth', 'GET', undefined, { bg: true })) } catch { /* tool tắt */ }
}

// Vai trò trong workspace đang chọn: VIEWER chỉ xem, EDITOR sửa lịch/rule/camp, OWNER thêm cài đặt và thành viên
export const role = () => state.auth.workspace?.role || 'OWNER'
export const canEdit = () => role() !== 'VIEWER'
export const isOwner = () => role() === 'OWNER'
// Đã đăng nhập nhưng chưa thuộc workspace nào (bị gỡ khỏi workspace, chưa được mời)
export const noWorkspace = () => state.auth.required && state.auth.authed && !state.auth.workspace

// Đổi workspace: tải lại toàn bộ dữ liệu (cài đặt, lịch, rule, camp đều là của workspace mới)
async function reloadAll() {
  await loadAuth()
  resetData()
  if (!noWorkspace()) { await loadState(); checkConn(true) }
}

export async function switchWorkspace(id) {
  await api('workspaces/switch', 'POST', { id })
  await reloadAll()
}

export async function createWorkspace(name) {
  await api('workspaces', 'POST', { name })
  await reloadAll()
}

export async function loadState() {
  const s = await api('state')
  state.settings = s.settings
  state.schedules = s.schedules
  state.rules = s.rules
  state.storage = s.storage || null
}

// Nơi lưu dữ liệu có đang ghi được không (chạy ngầm, chỉ cần khi lưu ở dịch vụ ngoài)
export async function loadStorage() {
  try { state.storage = await api('storage', 'GET', undefined, { bg: true }) } catch { /* mất kết nối tool: cảnh báo khác lo */ }
}

export async function loadObjs(force = false, bg = false) {
  state.objsLoading = true
  try {
    const r = await api('objects' + (force ? '?refresh=1' : ''), 'GET', undefined, { bg })
    state.objs = r.items
    state.objsErr = ''
    // giờ số liệu được tải từ Facebook (server có thể trả lại bản vừa tải để tiết kiệm lượt gọi)
    state.objsAt = r.at ? new Date(r.at) : new Date()
    state.objsMeta = { stale: !!r.stale, blockedUntil: r.blockedUntil || null, usage: r.usage || null, accounts: r.accounts || [], accountErrors: r.accountErrors || [] }
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
  if (!(state.auth.required && !state.auth.authed) && !noWorkspace()) {
    try { await loadState() } catch { /* hiển thị lỗi qua toast ở nơi gọi */ }
  }
  state.ready = true
  if (state.auth.authed) checkConn()
}

export async function afterLogin() {
  // Tải dữ liệu trước, bật giao diện chính sau cùng (loadAuth đặt authed=true) để không bị resetData xoá dở
  const a = await api('auth', 'GET', undefined, { bg: true })
  if (a.workspace) await loadState()
  resetData()
  Object.assign(state.auth, a)
  if (a.workspace) checkConn()
}

export async function logout() {
  await api('logout', 'POST')
  state.auth.authed = false
  state.auth.user = null; state.auth.workspace = null; state.auth.workspaces = []
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
