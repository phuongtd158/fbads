<script setup>
import { reactive, ref, computed } from 'vue'
import { Send, Save, FileText, CheckCircle2, XCircle, Users } from 'lucide-vue-next'
import { state, saveSettings } from '../../stores/app'
import { toast } from '../../stores/ui'
import { api } from '../../lib/api'
import { checkTelegramToken, checkTelegramChats, parseChatIds, isTime, MAX_TG_CHATS } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'
import Badge from '../../components/Badge.vue'

const f = reactive({ token: '', chatId: state.settings.telegramChatId || '', reportTime: state.settings.reportTime || '08:00' })
const submitted = ref(false)
const touched = reactive({})
const result = ref(null) // kết quả gửi thử / báo cáo gần nhất: [{ id, ok, error? }]

// Người nhận: nhiều Chat ID cách nhau bằng dấu phẩy. Hiện thành nhãn ngay khi gõ để thấy tool hiểu thế nào.
const recipients = computed(() => parseChatIds(f.chatId))
const invalidIds = computed(() => new Set(recipients.value.filter((id) => checkTelegramChats(id))))

// Luật: Bot Token và Chat ID phải đi cùng nhau (hoặc để trống cả hai để tắt thông báo)
const errs = computed(() => {
  const e = {}, tok = f.token.trim(), chat = f.chatId.trim(), hasTok = !!(tok || state.settings.has_telegramToken)
  const t = checkTelegramToken(tok); if (t) e.token = t
  const c = checkTelegramChats(chat); if (c) e.chat = c
  if (!e.token && !e.chat) {
    if (chat && !hasTok) e.token = 'Cần Bot Token để gửi tin nhắn'
    if (hasTok && !recipients.value.length) e.chat = 'Cần ít nhất một Chat ID để biết gửi tin cho ai'
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
  f.chatId = state.settings.telegramChatId || '' // server đã chuẩn hoá (bỏ trùng, cách nhau ", ")
  toast('Đã lưu cài đặt Telegram')
  return true
}
async function ready() {
  if (!await save()) return false
  if (!state.settings.has_telegramToken || !state.settings.telegramChatId) { toast('Cần nhập Bot Token và Chat ID trước', 'error'); return false }
  return true
}
// Gửi thử / gửi báo cáo: hiện kết quả từng người nhận (gửi được cho ít nhất một người là thành công một phần)
async function run(path, okOne, okMany) {
  if (!await ready()) return
  result.value = null
  try {
    const r = await api(path, 'POST')
    result.value = r.results || null
    const bad = (r.results || []).filter((x) => !x.ok)
    if (bad.length) toast(`Đã gửi cho ${r.sent}/${r.total} người nhận, ${bad.length} người lỗi (xem bên dưới)`, 'error')
    else toast(r.total > 1 ? okMany.replace('{n}', r.total) : okOne)
  } catch (e) {
    if (e.data && e.data.results) result.value = e.data.results // không ai nhận được: vẫn cho xem lý do từng người
    throw e
  }
}
const test = () => run('telegram/test', 'Đã gửi tin thử — kiểm tra Telegram', 'Đã gửi tin thử cho {n} người nhận')
const report = () => run('report', 'Đã gửi báo cáo', 'Đã gửi báo cáo cho {n} người nhận')
</script>

<template>
  <section class="card pad">
    <h3>Thông báo Telegram</h3>
    <p class="muted sub">Nhận tin mỗi khi tool thay đổi camp, kèm báo cáo tổng hợp mỗi sáng. Gửi được cho nhiều người cùng lúc. Không bắt buộc.</p>
    <div class="grid">
      <Field label="Bot Token" :error="show('token')"><template #aside><Badge v-if="state.settings.has_telegramToken" tone="success">đã lưu</Badge></template>
        <input v-model="f.token" class="input" type="password" autocomplete="off" :placeholder="state.settings.has_telegramToken ? 'Để trống = giữ token cũ' : '123456789:AAxxxxxxxx…'" @blur="touched.token = true" /></Field>
      <Field label="Báo cáo hằng ngày lúc" :error="show('time')"><input v-model="f.reportTime" class="input" type="time" @blur="touched.time = true" /></Field>
      <Field class="wide" label="Chat ID người nhận" :error="show('chat')" :hint="`Nhiều người thì cách nhau bằng dấu phẩy (tối đa ${MAX_TG_CHATS}). Mỗi người phải nhắn cho bot ít nhất một tin trước, hoặc bạn dùng Chat ID của một nhóm có bot.`">
        <input v-model="f.chatId" class="input" placeholder="Vd: 123456789, -100987654321, @kenhcuaban" autocomplete="off" @blur="touched.chat = true" />
        <div v-if="recipients.length" class="rcpt" aria-live="polite">
          <span class="cnt"><Users :size="14" />{{ recipients.length }} người nhận</span>
          <span v-for="id in recipients" :key="id" class="chip" :class="{ bad: invalidIds.has(id) }" :title="invalidIds.has(id) ? 'Chat ID này không hợp lệ' : ''">{{ id }}</span>
        </div>
      </Field>
    </div>
    <div class="btns">
      <Btn variant="primary" :icon="Save" :action="save">Lưu</Btn>
      <Btn :icon="Send" :action="test">Gửi tin thử</Btn>
      <Btn :icon="FileText" :action="report">Gửi báo cáo ngay</Btn>
    </div>
    <ul v-if="result" class="res" aria-live="polite">
      <li v-for="r in result" :key="r.id" :class="r.ok ? 'ok' : 'no'">
        <CheckCircle2 v-if="r.ok" :size="17" /><XCircle v-else :size="17" />
        <b>{{ r.id }}</b><span>{{ r.ok ? 'Đã gửi' : r.error }}</span>
      </li>
    </ul>
    <details>
      <summary>Cách lấy Bot Token và Chat ID</summary>
      <ol><li>Chat với <b>@BotFather</b>, gõ <code>/newbot</code> để lấy Bot Token.</li><li>Nhắn 1 tin bất kỳ cho bot vừa tạo.</li><li>Mở <code>https://api.telegram.org/bot&lt;TOKEN&gt;/getUpdates</code> và lấy số <code>chat.id</code>.</li>
        <li><b>Gửi cho nhiều người:</b> mỗi người nhắn 1 tin cho bot rồi lấy <code>chat.id</code> của họ ở <code>getUpdates</code>, nhập tất cả vào ô Chat ID cách nhau bằng dấu phẩy. Hoặc tạo một nhóm, thêm bot và mọi người vào, gửi 1 tin trong nhóm rồi lấy <code>chat.id</code> của nhóm (số âm, dạng <code>-100…</code>).</li></ol>
    </details>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
.grid { display: grid; grid-template-columns: 1fr 190px; gap: 0 14px; }
.grid .wide { grid-column: 1 / -1; }
.rcpt { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-top: 10px; }
.cnt { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 650; color: var(--text-2); margin-right: 4px; }
.chip { padding: 3px 11px; border-radius: 99px; background: var(--accent-soft); color: var(--accent); font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chip.bad { background: var(--danger-soft); color: var(--danger); }
.btns { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 4px; }
.res { list-style: none; margin: 16px 0 0; padding: 0; display: grid; gap: 6px; }
.res li { margin: 0; display: flex; align-items: flex-start; gap: 9px; padding: 9px 12px; border-radius: 10px; font-size: 14px; flex-wrap: wrap; }
.res li b { font-variant-numeric: tabular-nums; }
.res li span { flex: 1 1 200px; min-width: 0; }
.res li svg { flex: none; margin-top: 1px; }
.res .ok { background: var(--success-soft); color: var(--success); }
.res .no { background: var(--danger-soft); color: var(--danger); }
details { margin-top: 20px; padding: 12px 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }
summary { cursor: pointer; font-weight: 600; font-size: 14px; } ol { margin: 10px 0 0; padding-left: 20px; font-size: 14.5px; } li { margin-bottom: 6px; }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
