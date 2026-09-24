// Cột "Phân phối" (web/src/lib/delivery.js): suy ra giống Ads Manager từ camp + các nhóm QC bên trong.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { deliveryMap } from '../shared/delivery.mjs'

const NOW = Date.parse('2026-09-24T12:00:00Z')
const camp = (id, o = {}) => ({ id, level: 'campaign', status: 'ACTIVE', effective: 'ACTIVE', ...o })
const set = (id, campaignId, o = {}) => ({ id, level: 'adset', campaignId, status: 'ACTIVE', effective: 'ACTIVE', ...o })

test('camp bật nhưng mọi nhóm QC tắt → Nhóm quảng cáo đang tắt', () => {
  const m = deliveryMap([camp('c'), set('a1', 'c', { status: 'PAUSED', effective: 'PAUSED' }), set('a2', 'c', { status: 'PAUSED', effective: 'PAUSED' })], NOW)
  assert.equal(m.c, 'adsetsOff')
  assert.equal(m.a1, 'off')
})

test('camp có ít nhất 1 nhóm QC chạy (kể cả đang học) → Đang hoạt động', () => {
  assert.equal(deliveryMap([camp('c'), set('a1', 'c', { status: 'PAUSED', effective: 'PAUSED' }), set('a2', 'c')], NOW).c, 'active')
  const m = deliveryMap([camp('c'), set('a', 'c', { learning: true })], NOW)
  assert.equal(m.c, 'active')
  assert.equal(m.a, 'learning')
})

test('camp tắt → Tắt; nhóm QC bên trong → Chiến dịch đang tắt', () => {
  const m = deliveryMap([camp('c', { status: 'PAUSED', effective: 'PAUSED' }), set('a', 'c', { effective: 'CAMPAIGN_PAUSED' })], NOW)
  assert.equal(m.c, 'off')
  assert.equal(m.a, 'campaignOff')
})

test('lịch chạy của nhóm QC: chưa tới → Đã lên lịch, đã qua → Hoàn tất', () => {
  const future = set('f', 'c1', { startTime: NOW + 3600e3 }), done = set('d', 'c2', { endTime: NOW - 3600e3 })
  const m = deliveryMap([camp('c1'), future, camp('c2'), done], NOW)
  assert.equal(m.f, 'scheduled'); assert.equal(m.c1, 'scheduled')
  assert.equal(m.d, 'completed'); assert.equal(m.c2, 'completed')
})

test('trạng thái riêng của Facebook: bị từ chối, xét duyệt, lưu trữ', () => {
  const m = deliveryMap([camp('r', { effective: 'DISAPPROVED' }), camp('p', { effective: 'IN_PROCESS' }), camp('x', { effective: 'ARCHIVED', status: 'PAUSED' })], NOW)
  assert.deepEqual([m.r, m.p, m.x], ['rejected', 'review', 'archived'])
})

test('chưa có dữ liệu nhóm QC (dữ liệu giả) → theo trạng thái của camp', () => {
  const m = deliveryMap([camp('a'), camp('b', { status: 'PAUSED', effective: 'PAUSED' })], NOW)
  assert.deepEqual([m.a, m.b], ['active', 'off'])
})
