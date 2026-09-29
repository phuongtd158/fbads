import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseMoney, formatMoneyTyping as f, groupThousands } from '../shared/money.mjs'

test('parseMoney: các cách gõ số tiền thường gặp', () => {
  const cases = { '150000': 150000, '150.000': 150000, '150,000': 150000, '1.500.000': 1500000, '150k': 150000, '150 K': 150000, '1,5tr': 1500000, '1.5tr': 1500000,
    '2 triệu': 2000000, '1tỷ': 1e9, '200.000đ': 200000, '80 nghìn': 80000, '1.5': 2, '': '', '  ': '' }
  for (const [k, v] of Object.entries(cases)) assert.equal(parseMoney(k), v, k)
  assert.equal(parseMoney(1234.6), 1235)
  for (const bad of ['abc', '1,5xyz', '1.2.3', 'k']) assert.ok(Number.isNaN(parseMoney(bad)), bad)
})

test('formatMoneyTyping: gõ đến đâu thêm dấu chấm đến đó, chỉ nhận chữ số', () => {
  assert.equal(groupThousands('1000000'), '1.000.000')
  assert.equal(groupThousands('999'), '999')
  assert.deepEqual(f('1000000'), { text: '1.000.000', caret: 9 })
  assert.deepEqual(f('1.0000'), { text: '10.000', caret: 6 })
  assert.equal(f('150k').text, '150') // chữ cái bị bỏ
  assert.equal(f('200.000đ').text, '200.000')
  assert.equal(f('').text, '')
  assert.equal(f('0').text, '0')
  assert.equal(f('0012').text, '12')
  assert.equal(f('-50000').text, '50.000') // mặc định không cho số âm
  assert.equal(f('-50000', null, { negative: true }).text, '-50.000')
  assert.equal(f('-', null, { negative: true }).text, '-')
  assert.equal(f('1234567890123456789').text, '123.456.789.012.345')
  // con trỏ đứng sau đúng chữ số vừa gõ: chèn "5" vào "1.000|.000" thành "10.005.000"
  assert.deepEqual(f('1.0005.000', 6), { text: '10.005.000', caret: 6 })
  // xoá số 1 của "1.000" còn "0.000" thì về "0"
  assert.deepEqual(f('0.000', 0), { text: '0', caret: 0 })
  assert.deepEqual(f('-1000', 1, { negative: true }), { text: '-1.000', caret: 1 })
})
