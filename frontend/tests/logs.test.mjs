// Nhật ký realtime (src/shared/logs.mjs): dòng nhận qua WebSocket được gộp vào danh sách đang xem.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mergeLog } from '../src/shared/logs.mjs'

test('dòng mới chèn lên đầu', () => {
  assert.deepEqual(mergeLog([{ id: 'a' }], { id: 'b' }).map((l) => l.id), ['b', 'a'])
})

test('dòng đã có thì thay tại chỗ, không nhân đôi', () => {
  const list = [{ id: 'b' }, { id: 'a', undone: false }]
  const out = mergeLog(list, { id: 'a', undone: true })
  assert.deepEqual(out, [{ id: 'b' }, { id: 'a', undone: true }])
  assert.equal(list[1].undone, false) // không sửa mảng cũ
})

test('giữ tối đa max dòng, bỏ dòng cũ nhất', () => {
  const out = mergeLog([{ id: '2' }, { id: '1' }], { id: '3' }, 2)
  assert.deepEqual(out.map((l) => l.id), ['3', '2'])
})

test('bỏ qua sự kiện hỏng', () => {
  const list = [{ id: 'a' }]
  assert.equal(mergeLog(list, null), list)
  assert.equal(mergeLog(list, {}), list)
})
