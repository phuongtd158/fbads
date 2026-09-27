// Realtime qua WebSocket STOMP (/ws): server đẩy sự kiện khi có nhật ký mới, camp đổi trạng thái/ngân sách,
// xong một lượt tự động. Mất kết nối thì tự nối lại sau 5 giây; trong lúc đó giao diện vẫn tự làm mới định kỳ như cũ.
import { Client } from '@stomp/stompjs'

const TOPICS = ['logs', 'objects', 'engine']
const handlers = Object.fromEntries(TOPICS.map((t) => [t, new Set()]))
let client = null

/** Nghe một loại sự kiện. Trả về hàm huỷ (gọi trong onBeforeUnmount). */
export function onLive(type, fn) {
  handlers[type].add(fn)
  return () => handlers[type].delete(fn)
}

export function startLive() {
  if (client) return
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  client = new Client({
    brokerURL: `${proto}//${location.host}/ws`,
    reconnectDelay: 5000,
    onConnect: () => {
      for (const t of TOPICS) client.subscribe(`/topic/${t}`, (m) => {
        let data
        try { data = JSON.parse(m.body) } catch { return }
        for (const fn of handlers[t]) { try { fn(data) } catch (e) { console.error(e) } }
      })
    },
  })
  client.activate()
}

export function stopLive() {
  if (!client) return
  client.deactivate()
  client = null
}
