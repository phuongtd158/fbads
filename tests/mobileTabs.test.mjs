// Thanh menu dưới trên mobile: làm sạch lựa chọn đã lưu, bật/tắt, đổi thứ tự (shared/mobileTabs.mjs)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanTabs, toggleTab, moveTab, DEFAULT_TABS, MAX_TABS } from '../shared/mobileTabs.mjs'

test('chưa chọn gì hoặc dữ liệu hỏng thì dùng mặc định 5 mục cũ', () => {
  assert.deepEqual(DEFAULT_TABS, ['overview', 'schedules', 'rules', 'logs', 'settings'])
  assert.deepEqual(cleanTabs(undefined), DEFAULT_TABS)
  assert.deepEqual(cleanTabs('abc'), DEFAULT_TABS)
  assert.deepEqual(cleanTabs([]), DEFAULT_TABS)
})

test('bỏ mã lạ và trùng, luôn có Cài đặt, tối đa 5 mục, ít nhất 2 mục', () => {
  assert.deepEqual(cleanTabs(['company', 'xyz', 'company', 'settings']), ['company', 'settings'])
  assert.deepEqual(cleanTabs(['company', 'overview']), ['company', 'overview', 'settings'])
  assert.deepEqual(cleanTabs(['overview', 'schedules', 'rules', 'logs', 'company', 'help']), ['overview', 'schedules', 'rules', 'logs', 'settings'])
  assert.deepEqual(cleanTabs(['settings', 'overview', 'schedules', 'rules', 'logs', 'company']), ['settings', 'overview', 'schedules', 'rules', 'logs'])
  assert.deepEqual(cleanTabs(['settings']), DEFAULT_TABS)
  for (const l of [cleanTabs(['help', 'company', 'logs', 'rules', 'schedules', 'overview', 'settings'])]) {
    assert.ok(l.length <= MAX_TABS && l.includes('settings'))
  }
})

test('bật/tắt: không tắt được Cài đặt, đủ 5 mục thì không bật thêm, không xuống dưới 2 mục', () => {
  assert.deepEqual(toggleTab(DEFAULT_TABS, 'company'), DEFAULT_TABS, 'đã đủ 5 mục')
  const four = toggleTab(DEFAULT_TABS, 'logs')
  assert.deepEqual(four, ['overview', 'schedules', 'rules', 'settings'])
  assert.deepEqual(toggleTab(four, 'company'), ['overview', 'schedules', 'rules', 'settings', 'company'])
  assert.deepEqual(toggleTab(four, 'settings'), four)
  assert.deepEqual(toggleTab(['overview', 'settings'], 'overview'), ['overview', 'settings'])
})

test('đổi thứ tự lên/xuống, ở đầu/cuối thì giữ nguyên', () => {
  assert.deepEqual(moveTab(DEFAULT_TABS, 'settings', -1), ['overview', 'schedules', 'rules', 'settings', 'logs'])
  assert.deepEqual(moveTab(DEFAULT_TABS, 'overview', 1), ['schedules', 'overview', 'rules', 'logs', 'settings'])
  assert.deepEqual(moveTab(DEFAULT_TABS, 'overview', -1), DEFAULT_TABS)
  assert.deepEqual(moveTab(DEFAULT_TABS, 'settings', 1), DEFAULT_TABS)
  assert.deepEqual(moveTab(DEFAULT_TABS, 'help', 1), DEFAULT_TABS)
})
