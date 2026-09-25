import { test } from 'node:test'
import assert from 'node:assert/strict'
import { groupByCurrency, countByAccount, accountLabel, currencyOf, decimalsOf, labelOf } from '../shared/accounts.mjs'

const objs = [
  { id: 'a1', name: 'Camp A1', accountId: '111', accountName: 'Shop VN', currency: 'VND' },
  { id: 'b1', name: 'Camp B1', accountId: '222', accountName: 'US Store', currency: 'USD' },
  { id: 'a2', name: 'Camp A2', accountId: '111', accountName: 'Shop VN', currency: 'VND' },
  { id: 'c1', name: 'Camp C1', accountId: '333' }, // chưa biết tên/loại tiền
]

test('gom theo loại tiền: giữ thứ tự xuất hiện, loại tiền trống dùng giá trị dự phòng', () => {
  const g = groupByCurrency(objs, 'VND')
  assert.deepEqual(g.map((x) => [x.currency, x.items.map((o) => o.id)]), [['VND', ['a1', 'a2', 'c1']], ['USD', ['b1']]])
  assert.deepEqual(groupByCurrency([]), [])
  assert.equal(groupByCurrency(objs.slice(0, 1))[0].currency, 'VND')
  assert.equal(groupByCurrency([{ id: 'x' }], '')[0].currency, '') // không có gì để dựa vào
})

test('nhãn tài khoản: ưu tiên tên, không có thì dùng mã số', () => {
  assert.equal(accountLabel(objs[0]), 'Shop VN')
  assert.equal(accountLabel(objs[3]), '333')
  assert.equal(accountLabel({}), '')
  assert.equal(accountLabel(null), '')
  assert.equal(currencyOf(objs[3], 'VND'), 'VND')
})

test('đếm số mục theo tài khoản', () => {
  assert.deepEqual(countByAccount(objs), { 111: 2, 222: 1, 333: 1 })
  assert.deepEqual(countByAccount([{ id: 'x' }]), {}) // mục không có tài khoản không được đếm
})

test('số lẻ theo loại tiền', () => {
  assert.equal(decimalsOf('VND'), 0)
  assert.equal(decimalsOf('USD'), 2)
  assert.equal(decimalsOf(''), 2)
})

test('nhãn của mục theo id: chỉ thêm tên tài khoản khi có nhiều tài khoản', () => {
  assert.deepEqual(labelOf(objs, 'b1', true), { name: 'Camp B1', account: 'US Store', full: 'Camp B1 · US Store' })
  assert.deepEqual(labelOf(objs, 'b1', false), { name: 'Camp B1', account: '', full: 'Camp B1' })
  assert.deepEqual(labelOf(objs, 'da-xoa', true), { name: 'da-xoa', account: '', full: 'da-xoa' }) // mục không còn → hiện id
  assert.deepEqual(labelOf(null, 'x', true), { name: 'x', account: '', full: 'x' })
})
