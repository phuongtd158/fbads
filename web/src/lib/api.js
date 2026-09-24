import { reactive } from 'vue'

// net.pending > 0 => thanh tiến trình trên cùng chạy
export const net = reactive({ pending: 0 })

export class ApiError extends Error {
  constructor(message, { silent = false } = {}) {
    super(message)
    this.silent = silent
  }
}

let unauthorizedHandler = () => {}
export const onUnauthorized = (fn) => { unauthorizedHandler = fn }

export async function api(path, method = 'GET', body, opts = {}) {
  if (!opts.bg) net.pending++
  try {
    let res
    try {
      res = await fetch('/api/' + path, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
      })
    } catch {
      throw new ApiError('Không kết nối được tới tool. Cửa sổ chạy tool (start.bat) có đang mở không?')
    }
    let json = {}
    try { json = await res.json() } catch { /* body rỗng */ }
    if (res.status === 401 && path !== 'login') {
      unauthorizedHandler()
      throw new ApiError('Cần đăng nhập', { silent: true })
    }
    if (!res.ok || json.error) throw new ApiError(json.error || 'Có lỗi xảy ra')
    return json
  } finally {
    if (!opts.bg) net.pending--
  }
}
