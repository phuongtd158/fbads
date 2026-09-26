import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseMoney } from '../shared/money.mjs'

test('parseMoney: các cách gõ số tiền thường gặp', () => {
  const cases = { '150000': 150000, '150.000': 150000, '150,000': 150000, '1.500.000': 1500000, '150k': 150000, '150 K': 150000, '1,5tr': 1500000, '1.5tr': 1500000,
    '2 triệu': 2000000, '1tỷ': 1e9, '200.000đ': 200000, '80 nghìn': 80000, '1.5': 2, '': '', '  ': '' }
  for (const [k, v] of Object.entries(cases)) assert.equal(parseMoney(k), v, k)
  assert.equal(parseMoney(1234.6), 1235)
  for (const bad of ['abc', '1,5xyz', '1.2.3', 'k']) assert.ok(Number.isNaN(parseMoney(bad)), bad)
})
