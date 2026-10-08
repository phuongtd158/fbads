// Thứ tự cột kéo thả của bảng Tổng quan (shared/columnOrder.mjs)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanOrder, toggleKey, moveKey, canonicalOrder, isCanonical } from '../shared/columnOrder.mjs'

const KEYS = ['budget', 'spend', 'results', 'cpa', 'roas', 'ctr']
const DEF = ['budget', 'spend', 'results']

test('giữ nguyên thứ tự đã lưu, bỏ key lạ và trùng', () => {
  assert.deepEqual(cleanOrder(['cpa', 'spend', 'xxx', 'cpa', 'budget'], KEYS, DEF), ['cpa', 'spend', 'budget'])
})

test('dữ liệu hỏng hoặc rỗng thì về mặc định', () => {
  assert.deepEqual(cleanOrder(undefined, KEYS, DEF), DEF)
  assert.deepEqual(cleanOrder('abc', KEYS, DEF), DEF)
  assert.deepEqual(cleanOrder(['xxx'], KEYS, DEF), DEF)
})

test('bật cột mới thì nằm cuối, tắt thì bỏ, không tắt được cột cuối cùng', () => {
  assert.deepEqual(toggleKey(['cpa', 'spend'], 'budget'), ['cpa', 'spend', 'budget'])
  assert.deepEqual(toggleKey(['cpa', 'spend'], 'cpa'), ['spend'])
  assert.deepEqual(toggleKey(['spend'], 'spend'), ['spend'])
})

test('kéo cột tới vị trí mới', () => {
  const l = ['budget', 'spend', 'results', 'cpa']
  assert.deepEqual(moveKey(l, 'cpa', 0), ['cpa', 'budget', 'spend', 'results'])
  assert.deepEqual(moveKey(l, 'budget', 3), ['spend', 'results', 'cpa', 'budget'])
  assert.deepEqual(moveKey(l, 'spend', 2), ['budget', 'results', 'spend', 'cpa'])
  assert.deepEqual(moveKey(l, 'spend', 99), ['budget', 'results', 'cpa', 'spend'])
  assert.equal(moveKey(l, 'spend', 1), l) // không đổi
  assert.equal(moveKey(l, 'xxx', 0), l)
})

test('đặt lại thứ tự chuẩn nhưng giữ các cột đang bật', () => {
  assert.deepEqual(canonicalOrder(['roas', 'budget', 'cpa'], KEYS), ['budget', 'cpa', 'roas'])
  assert.equal(isCanonical(['budget', 'cpa', 'roas'], KEYS), true)
  assert.equal(isCanonical(['roas', 'budget'], KEYS), false)
})
