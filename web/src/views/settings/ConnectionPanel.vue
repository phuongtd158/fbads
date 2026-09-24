<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { CheckCircle2, AlertTriangle, FlaskConical, Loader2, KeyRound, RefreshCw, ExternalLink, X, Check, ShieldCheck } from 'lucide-vue-next'
import { state, checkConn, loadState, resetData } from '../../stores/app'
import { toast, toastError } from '../../stores/ui'
import { api } from '../../lib/api'
import Btn from '../../components/Btn.vue'
import Badge from '../../components/Badge.vue'
import Segmented from '../../components/Segmented.vue'

const wizOpen = ref(false)
const method = ref('fast')
const token = ref('')
const info = ref(null)
const acc = ref('')
const manual = ref('')
const loading = ref(false)
const ext = reactive({ appId: '', secret: '' })
const wiz = ref(null)

const s = computed(() => state.settings)
const conn = computed(() => state.conn)
const tk = computed(() => (conn.value && conn.value.token) || null)
const tokenText = computed(() => {
  const t = tk.value
  if (!t) return ''
  if (t.daysLeft == null) return 'Không hết hạn'
  return t.daysLeft >= 1 ? `Còn ${t.daysLeft} ngày (đến ${new Date(t.expiresAt).toLocaleDateString('vi-VN')})` : 'Hết hạn trong hôm nay'
})
const tokenTone = computed(() => { const t = tk.value; return !t || t.daysLeft == null || t.daysLeft > 7 ? 'ok' : t.daysLeft >= 0 ? 'warn' : 'bad' })
const isShort = computed(() => { const t = info.value && info.value.token; return !!(t && t.expiresAt && t.expiresAt - Date.now() < 12 * 3600e3) })
const hasToken = computed(() => !!(token.value.trim() || s.value.has_accessToken))

onMounted(() => { if (!s.value.mock) checkConn(true) })

async function recheck() { await checkConn(true); if (state.conn && state.conn.ok) toast('Kết nối hoạt động tốt') }
function openWiz() { wizOpen.value = true; info.value = null; acc.value = s.value.adAccountId || ''; nextTick(() => wiz.value && wiz.value.scrollIntoView({ behavior: 'smooth', block: 'start' })) }
function closeWiz() { wizOpen.value = false; token.value = ''; info.value = null }
defineExpose({ openWiz })

async function verify() {
  const t = token.value.trim()
  if (!t && !s.value.has_accessToken) return toast('Hãy dán Access Token trước', 'error')
  loading.value = true; info.value = null
  try {
    info.value = await api('fb/accounts', 'POST', { token: t })
    if (!acc.value && info.value.accounts.length === 1) acc.value = info.value.accounts[0].id
  } catch (e) { toastError(e) } finally { loading.value = false }
}
async function extend() {
  if (!ext.appId.trim() || !ext.secret.trim()) return toast('Nhập App ID và App Secret', 'error')
  loading.value = true
  try {
    await api('fb/extend', 'POST', { token: token.value.trim(), appId: ext.appId.trim(), appSecret: ext.secret.trim() })
    token.value = ''; await loadState(); toast('Đã gia hạn token lên khoảng 60 ngày')
  } catch (e) { toastError(e); loading.value = false; return }
  loading.value = false
  await verify()
}
function onManual() { acc.value = manual.value.trim().replace(/^act_/, '') }
async function saveConn() {
  if (!acc.value) return toast('Hãy chọn tài khoản quảng cáo', 'error')
  const wasMock = s.value.mock
  const body = { adAccountId: acc.value, mock: false, dryRun: wasMock ? true : s.value.dryRun }
  if (token.value.trim()) body.accessToken = token.value.trim()
  await api('settings', 'POST', body)
  await loadState(); resetData(); closeWiz()
  await checkConn(true)
  toast(wasMock ? 'Đã kết nối! Đang ở chế độ Chạy thử (an toàn, chưa thay đổi camp thật)' : 'Đã lưu kết nối')
}
</script>

<template>
  <div class="wrap">
    <!-- Thẻ trạng thái -->
    <section v-if="s.mock" class="card st warn">
      <span class="ci"><FlaskConical :size="24" /></span>
      <div class="cb"><h3>Chưa kết nối Facebook</h3><p class="muted">Đang dùng dữ liệu giả để làm quen. Kết nối Facebook thật để điều khiển camp của bạn — mất khoảng 3 phút.</p></div>
      <Btn variant="primary" :icon="KeyRound" @click="openWiz">Kết nối Facebook</Btn>
    </section>
    <section v-else-if="!conn" class="card st">
      <span class="ci"><Loader2 class="spin" :size="24" /></span>
      <div class="cb"><h3>Đang kiểm tra kết nối…</h3><p class="muted">Tool đang hỏi Facebook xem token và tài khoản có hợp lệ không.</p></div>
    </section>
    <section v-else-if="!conn.ok" class="card st bad">
      <span class="ci"><AlertTriangle :size="24" /></span>
      <div class="cb"><h3>Kết nối Facebook chưa hoạt động</h3><p class="muted">{{ conn.error }}</p></div>
      <div class="btns"><Btn :icon="RefreshCw" :action="recheck">Kiểm tra lại</Btn><Btn variant="primary" @click="openWiz">Sửa kết nối</Btn></div>
    </section>
    <section v-else class="card st good">
      <span class="ci"><CheckCircle2 :size="24" /></span>
      <div class="cb">
        <h3>Đã kết nối Facebook</h3>
        <p class="faint">Kiểm tra lúc {{ new Date(conn.checkedAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) }}</p>
        <dl>
          <div><dt>Người dùng</dt><dd>{{ conn.user }}</dd></div>
          <div><dt>Tài khoản QC</dt><dd>{{ conn.name }} · {{ conn.currency }} <Badge :tone="conn.accountActive ? 'success' : 'warning'" dot>{{ conn.status }}</Badge></dd></div>
          <div v-if="tk"><dt>Quyền truy cập</dt><dd><template v-if="tk.missing && tk.missing.length"><Badge tone="danger">Thiếu {{ tk.missing.join(', ') }}</Badge></template><template v-else>ads_management, ads_read <ShieldCheck :size="15" class="okc" /></template></dd></div>
          <div v-if="tk"><dt>Access Token</dt><dd><Badge :tone="tokenTone === 'ok' ? 'success' : tokenTone === 'warn' ? 'warning' : 'danger'">{{ tokenText }}</Badge></dd></div>
        </dl>
      </div>
      <div class="btns"><Btn :icon="RefreshCw" :action="recheck">Kiểm tra lại</Btn><Btn @click="openWiz">Đổi token / tài khoản</Btn></div>
    </section>

    <!-- Trình hướng dẫn -->
    <Transition name="wz">
      <section v-if="wizOpen" ref="wiz" class="card wz">
        <header><span class="hi"><KeyRound :size="20" /></span><h3>Kết nối Facebook</h3><Btn variant="ghost" size="sm" :icon="X" aria-label="Đóng" @click="closeWiz" /></header>

        <div class="step">
          <span class="no">1</span>
          <div class="body">
            <h4>Lấy Access Token</h4>
            <p class="muted">Token là “chìa khoá” cho phép tool điều khiển quảng cáo của bạn. Chọn cách phù hợp:</p>
            <Segmented v-model="method" :options="[{ value: 'fast', label: 'Cách nhanh (60 ngày)' }, { value: 'stable', label: 'Cách ổn định (không hết hạn)' }]" />
            <ol v-if="method === 'fast'">
              <li><div>Mở <b>Graph API Explorer</b> của Facebook (đăng nhập đúng tài khoản đang chạy quảng cáo).<a class="ext" href="https://developers.facebook.com/tools/explorer/" target="_blank" rel="noopener">Mở Graph API Explorer <ExternalLink :size="13" /></a></div></li>
              <li><div>Ở ô <b>Meta App</b> chọn ứng dụng của bạn. Chưa có? Tạo nhanh, chọn loại <b>Business</b>.<a class="ext" href="https://developers.facebook.com/apps/creation/" target="_blank" rel="noopener">Tạo ứng dụng mới <ExternalLink :size="13" /></a></div></li>
              <li><div>Ở mục <b>Permissions</b> thêm 2 quyền <code>ads_management</code> và <code>ads_read</code>, rồi bấm <b>Generate Access Token</b> và cho phép.</div></li>
              <li><div>Sao chép chuỗi token (bắt đầu bằng <code>EAA…</code>) và dán vào bước 2. Token này chỉ sống vài giờ, tool sẽ giúp bạn <b>gia hạn lên 60 ngày</b>.</div></li>
            </ol>
            <ol v-else>
              <li><div>Mở <b>Cài đặt doanh nghiệp → Người dùng hệ thống</b> và bấm <b>Thêm</b> (vai trò Quản trị viên).<a class="ext" href="https://business.facebook.com/settings/system-users" target="_blank" rel="noopener">Mở Người dùng hệ thống <ExternalLink :size="13" /></a></div></li>
              <li><div>Chọn người dùng vừa tạo → <b>Thêm tài sản</b> → <b>Tài khoản quảng cáo</b> → bật quyền <b>Quản lý chiến dịch</b>.</div></li>
              <li><div>Bấm <b>Tạo mã truy cập mới</b>, chọn ứng dụng, tick <code>ads_management</code> và <code>ads_read</code>, đặt hạn <b>Không bao giờ</b>.</div></li>
              <li><div>Sao chép token và dán vào bước 2. Token này <b>không hết hạn</b> nên ổn định nhất.</div></li>
            </ol>
          </div>
        </div>

        <div class="step" :class="{ done: info }">
          <span class="no"><Check v-if="info" :size="16" /><template v-else>2</template></span>
          <div class="body">
            <h4>Dán token và kiểm tra</h4>
            <p class="muted">Tool sẽ kiểm tra token còn dùng được không và có đủ quyền không.</p>
            <div class="row">
              <input v-model="token" class="input" type="password" autocomplete="off" :placeholder="s.has_accessToken ? 'Đã có token đã lưu — dán token mới hoặc bấm Kiểm tra' : 'Dán token vào đây (bắt đầu bằng EAA…)'" @keydown.enter="verify" />
              <Btn variant="primary" :loading="loading" :action="verify">Kiểm tra token</Btn>
            </div>
            <div v-if="info" class="note ok"><CheckCircle2 :size="18" /><div><b>Token hợp lệ</b> — xin chào {{ info.user }}.<template v-if="info.token"> {{ info.token.daysLeft == null ? 'Token không hết hạn.' : info.token.daysLeft >= 1 ? `Còn ${info.token.daysLeft} ngày.` : 'Token sắp hết hạn.' }}</template></div></div>
            <div v-if="info && info.token && info.token.missing && info.token.missing.length" class="note bad"><AlertTriangle :size="18" /><div><b>Thiếu quyền: {{ info.token.missing.join(', ') }}.</b> Quay lại bước 1, thêm đủ quyền rồi tạo token mới.</div></div>
            <div v-if="isShort" class="note warn">
              <AlertTriangle :size="18" />
              <div>
                <b>Token này chỉ sống vài giờ.</b> Nhập App ID và App Secret để đổi thành token 60 ngày (chỉ dùng 1 lần, tool không lưu lại).
                <div class="row" style="margin-top: 10px"><input v-model="ext.appId" class="input" placeholder="App ID" /><input v-model="ext.secret" class="input" type="password" placeholder="App Secret" /><Btn size="sm" :loading="loading" :action="extend">Gia hạn 60 ngày</Btn></div>
                <small>Lấy ở <b>developers.facebook.com → ứng dụng của bạn → Cài đặt → Cơ bản</b>.</small>
              </div>
            </div>
          </div>
        </div>

        <div class="step" :class="{ done: acc && info }">
          <span class="no"><Check v-if="acc && info" :size="16" /><template v-else>3</template></span>
          <div class="body">
            <h4>Chọn tài khoản quảng cáo</h4>
            <p class="muted">{{ info ? 'Chọn tài khoản mà tool sẽ quản lý.' : 'Danh sách sẽ hiện ra sau khi kiểm tra token ở bước 2.' }}</p>
            <div v-if="info && info.accounts.length" class="accs">
              <label v-for="a in info.accounts" :key="a.id" class="acc" :class="{ on: acc === a.id }">
                <input v-model="acc" type="radio" name="acc" :value="a.id" />
                <div class="x"><b>{{ a.name }}</b><small class="faint">ID {{ a.id }} · {{ a.currency }}</small></div>
                <Badge :tone="a.active ? 'success' : 'warning'" dot>{{ a.status }}</Badge>
              </label>
            </div>
            <div v-else-if="info" class="note warn"><AlertTriangle :size="18" /><div><b>Token này chưa thấy tài khoản quảng cáo nào.</b> Hãy đảm bảo tài khoản đã được gán cho người dùng/token này, hoặc nhập ID thủ công bên dưới.</div></div>
            <details><summary>Nhập ID tài khoản thủ công</summary>
              <div class="row"><input v-model="manual" class="input" placeholder="Vd: 1234567890" @input="onManual" /><small class="faint">ID nằm cạnh tên tài khoản trong Ads Manager.</small></div>
            </details>
          </div>
        </div>

        <footer><Btn @click="closeWiz">Huỷ</Btn><Btn variant="primary" :icon="Check" :disabled="!acc || !hasToken" :action="saveConn">Lưu & kết nối</Btn></footer>
      </section>
    </Transition>
  </div>
</template>

<style scoped>
.wrap { display: grid; gap: 18px; }
.st { display: flex; gap: 18px; align-items: flex-start; padding: 22px 24px; flex-wrap: wrap; }
.ci { width: 50px; height: 50px; border-radius: 17px; display: grid; place-items: center; flex: none; background: var(--surface-3); color: var(--text-2); }
.good { border-color: color-mix(in srgb, var(--success) 35%, var(--border)); } .good .ci { background: var(--success-soft); color: var(--success); }
.warn { border-color: color-mix(in srgb, var(--warning) 35%, var(--border)); } .warn .ci { background: var(--warning-soft); color: var(--warning); }
.bad { border-color: color-mix(in srgb, var(--danger) 40%, var(--border)); } .bad .ci { background: var(--danger-soft); color: var(--danger); }
.cb { flex: 1; min-width: 240px; } .cb h3 { font-size: 18px; letter-spacing: -.02em; }
.btns { display: flex; gap: 10px; flex-wrap: wrap; }
dl { margin: 14px 0 0; display: grid; gap: 0; }
dl > div { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--border); }
dt { width: 130px; color: var(--text-2); font-size: 14px; flex: none; } dd { margin: 0; font-weight: 600; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.okc { color: var(--success); }

.wz { overflow: hidden; }
.wz > header { display: flex; align-items: center; gap: 12px; padding: 18px 24px; border-bottom: 1px solid var(--border); }
.wz > header h3 { flex: 1; font-size: 18px; letter-spacing: -.02em; }
.hi { width: 38px; height: 38px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); display: grid; place-items: center; }
.step { display: flex; gap: 18px; padding: 24px; border-bottom: 1px solid var(--border); }
.no { width: 32px; height: 32px; border-radius: 50%; background: var(--accent-grad); color: #fff; display: grid; place-items: center; font-weight: 700; font-size: 14px; flex: none; }
.done .no { background: var(--success); }
.body { flex: 1; min-width: 0; } .body h4 { font-size: 16.5px; margin: 3px 0 4px; letter-spacing: -.01em; } .body > p { margin-bottom: 14px; font-size: 14.5px; }
ol { list-style: none; counter-reset: s; margin: 16px 0 0; padding: 0; }
li { counter-increment: s; display: flex; gap: 12px; margin-bottom: 14px; font-size: 14.5px; }
li::before { content: counter(s); width: 24px; height: 24px; border-radius: 50%; background: var(--surface-3); color: var(--text-2); font-weight: 700; font-size: 12.5px; display: grid; place-items: center; flex: none; margin-top: 1px; }
li > div { flex: 1; min-width: 0; }
.ext { display: inline-flex; align-items: center; gap: 6px; margin-top: 8px; padding: 6px 12px; border-radius: 9px; border: 1px solid var(--border-strong); background: var(--surface); color: var(--text); font-weight: 600; font-size: 13.5px; text-decoration: none; transition: .15s; }
.ext:hover { border-color: var(--accent); color: var(--accent); }
.ext { display: flex; width: fit-content; }
.row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; } .row .input { flex: 1; min-width: 200px; }
.note { display: flex; gap: 11px; padding: 12px 14px; border-radius: 12px; margin-top: 12px; font-size: 14.5px; }
.note > div { flex: 1; min-width: 0; } .note svg { flex: none; margin-top: 2px; } .note small { display: block; margin-top: 6px; opacity: .85; }
.note.ok { background: var(--success-soft); color: var(--success); } .note.bad { background: var(--danger-soft); color: var(--danger); } .note.warn { background: var(--warning-soft); color: var(--warning); }
.accs { display: grid; gap: 8px; margin-bottom: 12px; }
.acc { display: flex; align-items: center; gap: 12px; padding: 13px 15px; border: 1.5px solid var(--border); border-radius: 14px; cursor: pointer; transition: .15s; }
.acc:hover { border-color: var(--border-strong); } .acc.on { border-color: var(--accent); background: var(--accent-soft); }
.acc input { accent-color: var(--accent); width: 18px; height: 18px; flex: none; }
.x { flex: 1; min-width: 0; } .x b { display: block; } .x small { font-size: 13px; }
details { margin-top: 10px; padding: 12px 16px; border: 1px solid var(--border); border-radius: 12px; }
details summary { cursor: pointer; font-weight: 600; font-size: 14px; } details .row { margin-top: 10px; }
.wz > footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; background: var(--surface-2); }
.wz-enter-active, .wz-leave-active { transition: all .35s var(--ease); }
.wz-enter-from, .wz-leave-to { opacity: 0; transform: translateY(-10px); }
@media (max-width: 640px) { .step { flex-direction: column; gap: 10px; padding: 18px; } dt { width: 100px; } }
</style>
