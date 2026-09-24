<script setup>
import { reactive, ref, computed } from 'vue'
import { Send, Save, FileText } from 'lucide-vue-next'
import { state, saveSettings } from '../../stores/app'
import { toast } from '../../stores/ui'
import { api } from '../../lib/api'
import { checkTelegramToken, checkTelegramChat, isTime } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'
import Badge from '../../components/Badge.vue'

const f = reactive({ token: '', chatId: state.settings.telegramChatId || '', reportTime: state.settings.reportTime || '08:00' })
const submitted = ref(false)
const touched = reactive({})

// Luật: Bot Token và Chat ID phải đi cùng nhau (hoặc để trống cả hai để tắt thông báo)
const errs = computed(() => {
  const e = {}, tok = f.token.trim(), chat = f.chatId.trim(), hasTok = !!(tok || state.settings.has_telegramToken)
  const t = checkTelegramToken(tok); if (t) e.token = t
  const c = checkTelegramChat(chat); if (c) e.chat = c
  if (!e.token && !e.chat) {
    if (chat && !hasTok) e.token = 'Cần Bot Token để gửi tin nhắn'
    if (hasTok && !chat) e.chat = 'Cần Chat ID để biết gửi tin cho ai'
  }
  if (f.reportTime && !isTime(f.reportTime)) e.time = 'Giờ báo cáo không hợp lệ'
  return e
})
const show = (k) => (submitted.value || touched[k] ? errs.value[k] : '')

async function save() {
  submitted.value = true
  if (Object.keys(errs.value).length) { toast('Hãy sửa các mục báo lỗi trước khi lưu', 'error'); return false }
  await saveSettings({ telegramToken: f.token.trim(), telegramChatId: f.chatId.trim(), reportTime: f.reportTime })
  f.token = ''
  toast('Đã lưu cài đặt Telegram')
  return true
}
async function ready() {
  if (!await save()) return false
  if (!state.settings.has_telegramToken || !state.settings.telegramChatId) { toast('Cần nhập Bot Token và Chat ID trước', 'error'); return false }
  return true
}
const test = async () => { if (await ready()) { await api('telegram/test', 'POST'); toast('Đã gửi tin thử — kiểm tra Telegram') } }
const report = async () => { if (await ready()) { await api('report', 'POST'); toast('Đã gửi báo cáo') } }
</script>

<template>
  <section class="card pad">
    <h3>Thông báo Telegram</h3>
    <p class="muted sub">Nhận tin mỗi khi tool thay đổi camp, kèm báo cáo tổng hợp mỗi sáng. Không bắt buộc.</p>
    <div class="grid">
      <Field label="Bot Token" :error="show('token')"><template #aside><Badge v-if="state.settings.has_telegramToken" tone="success">đã lưu</Badge></template>
        <input v-model="f.token" class="input" type="password" autocomplete="off" :placeholder="state.settings.has_telegramToken ? 'Để trống = giữ token cũ' : '123456789:AAxxxxxxxx…'" @blur="touched.token = true" /></Field>
      <Field label="Chat ID" :error="show('chat')"><input v-model="f.chatId" class="input" placeholder="Vd: 123456789" @blur="touched.chat = true" /></Field>
      <Field label="Báo cáo hằng ngày lúc" :error="show('time')"><input v-model="f.reportTime" class="input" type="time" @blur="touched.time = true" /></Field>
    </div>
    <div class="btns">
      <Btn variant="primary" :icon="Save" :action="save">Lưu</Btn>
      <Btn :icon="Send" :action="test">Gửi tin thử</Btn>
      <Btn :icon="FileText" :action="report">Gửi báo cáo ngay</Btn>
    </div>
    <details>
      <summary>Cách lấy Bot Token và Chat ID</summary>
      <ol><li>Chat với <b>@BotFather</b>, gõ <code>/newbot</code> để lấy Bot Token.</li><li>Nhắn 1 tin bất kỳ cho bot vừa tạo.</li><li>Mở <code>https://api.telegram.org/bot&lt;TOKEN&gt;/getUpdates</code> và lấy số <code>chat.id</code>.</li></ol>
    </details>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.grid { display: grid; grid-template-columns: 1.4fr 1fr 170px; gap: 14px; }
.btns { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 4px; }
details { margin-top: 20px; padding: 12px 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }
summary { cursor: pointer; font-weight: 600; font-size: 14px; } ol { margin: 10px 0 0; padding-left: 20px; font-size: 14.5px; } li { margin-bottom: 6px; }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
