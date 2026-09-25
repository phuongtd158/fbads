<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CheckCircle2, AlertTriangle, FlaskConical, Loader2, KeyRound, RefreshCw, ExternalLink, X, Check, ShieldCheck, Copy, LogIn, ListPlus } from 'lucide-vue-next'
import { state, checkConn, loadState, resetData } from '../../stores/app'
import { toast, toastError } from '../../stores/ui'
import { api } from '../../lib/api'
import { checkToken, checkAppId, checkAppSecret, checkConfigId, checkAccountId, cleanAccountId } from '../../lib/validate'
import Btn from '../../components/Btn.vue'
import Badge from '../../components/Badge.vue'
import Segmented from '../../components/Segmented.vue'
import InfoTip from '../../components/InfoTip.vue'

const route = useRoute()
const router = useRouter()
const wizOpen = ref(false)
const method = ref('login')
const token = ref('')
const info = ref(null)
const accs = ref([]) // các tài khoản quảng cáo được chọn (quản lý nhiều tài khoản cùng lúc)
const manual = ref('')
const loading = ref(false)
const ext = reactive({ appId: '', secret: '' })
const wiz = ref(null)
const step3 = ref(null)
const tokErr = ref('')
const manualErr = ref('')

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

// Đăng nhập bằng Facebook
const oauth = reactive({ appId: '', secret: '', configId: '', redirectUri: '' })
const oauthErr = ref('')
const OAUTH_RESULT = {
  cancel: ['Bạn đã huỷ đăng nhập Facebook, chưa có gì thay đổi.', 'error'],
  expired: ['Phiên đăng nhập Facebook đã quá 10 phút hoặc tool vừa khởi động lại. Hãy bấm Đăng nhập bằng Facebook lần nữa.', 'error'],
}

async function loadOauth() {
  oauth.appId = s.value.fbAppId || ''
  oauth.configId = s.value.fbConfigId || ''
  try { oauth.redirectUri = (await api('fb/oauth', 'GET', null, { bg: true })).redirectUri } catch { oauth.redirectUri = `${location.origin}/api/fb/callback` }
}
async function copyUri() {
  try { await navigator.clipboard.writeText(oauth.redirectUri); toast('Đã sao chép địa chỉ') } catch { toast('Không sao chép được, hãy bôi đen và sao chép tay', 'error') }
}
async function fbLogin() {
  const secret = oauth.secret.trim()
  oauthErr.value = checkAppId(oauth.appId) ||
    (secret ? checkAppSecret(secret) : s.value.has_fbAppSecret && oauth.appId.trim() === s.value.fbAppId ? '' : 'Hãy nhập App Secret') ||
    checkConfigId(oauth.configId)
  if (oauthErr.value) return
  const { url } = await api('fb/oauth/start', 'POST', { appId: oauth.appId.trim(), appSecret: secret, configId: oauth.configId.trim() })
  location.href = url
}
// Facebook chuyển về /#/settings/connection?fbLogin=ok|cancel|expired|fail
async function handleOauthReturn(r) {
  router.replace({ query: {} })
  if (r === 'ok') {
    await loadState()
    openWiz(); method.value = 'login'
    toast('Đăng nhập Facebook thành công, token đã được lưu')
    await verify()
    if (!s.value.mock) checkConn(true)
    return
  }
  if (OAUTH_RESULT[r]) return toast(...OAUTH_RESULT[r])
  let msg = ''
  try { msg = (await api('fb/oauth')).error } catch { /* bỏ qua */ }
  openWiz(); method.value = 'login'
  oauthErr.value = `Đăng nhập Facebook thất bại: ${msg || 'lỗi không xác định'}`
}

onMounted(() => {
  loadOauth()
  const r = route.query.fbLogin
  if (r) return handleOauthReturn(String(r))
  if (!s.value.mock) checkConn(true)
})

async function recheck() { await checkConn(true); if (state.conn && state.conn.ok) toast('Kết nối hoạt động tốt') }
function openWiz() { wizOpen.value = true; info.value = null; accs.value = [...(s.value.adAccountIds && s.value.adAccountIds.length ? s.value.adAccountIds : s.value.adAccountId ? [s.value.adAccountId] : [])]; nextTick(() => wiz.value && wiz.value.scrollIntoView({ behavior: 'smooth', block: 'start' })) }
// Mở thẳng phần chọn tài khoản bằng token đã lưu (không phải làm lại bước lấy token)
async function openAccounts() {
  openWiz()
  if (!s.value.has_accessToken) return
  await verify()
  await nextTick()
  if (step3.value) step3.value.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function closeWiz() { wizOpen.value = false; token.value = ''; info.value = null }
defineExpose({ openWiz })

async function verify() {
  const t = token.value.trim()
  tokErr.value = t || !s.value.has_accessToken ? checkToken(t) : '' // dán mới thì kiểm tra định dạng; bỏ trống thì dùng token đã lưu
  if (tokErr.value) return
  loading.value = true; info.value = null
  try {
    info.value = await api('fb/accounts', 'POST', { token: t })
    if (!accs.value.length && info.value.accounts.length === 1) accs.value = [info.value.accounts[0].id]
  } catch (e) { toastError(e) } finally { loading.value = false }
}
async function extend() {
  const bad = checkToken(token.value.trim() || (s.value.has_accessToken ? 'x'.repeat(20) : '')) || checkAppId(ext.appId) || checkAppSecret(ext.secret)
  if (bad) return toast(bad, 'error')
  loading.value = true
  try {
    await api('fb/extend', 'POST', { token: token.value.trim(), appId: ext.appId.trim(), appSecret: ext.secret.trim() })
    token.value = ''; await loadState(); toast('Đã gia hạn token lên khoảng 60 ngày')
  } catch (e) { toastError(e); loading.value = false; return }
  loading.value = false
  await verify()
}
const toggleAcc = (id) => { accs.value = accs.value.includes(id) ? accs.value.filter((x) => x !== id) : [...accs.value, id] }
const listed = computed(() => (info.value ? info.value.accounts.map((a) => a.id) : []))
const allAccs = computed(() => listed.value.length > 0 && listed.value.every((id) => accs.value.includes(id)))
const someAccs = computed(() => !allAccs.value && listed.value.some((id) => accs.value.includes(id)))
function toggleAllAccs() {
  accs.value = allAccs.value ? accs.value.filter((id) => !listed.value.includes(id)) : [...new Set([...accs.value, ...listed.value])]
}
// ID nhập tay (hoặc đã chọn trước đây) nhưng không có trong danh sách token thấy được
const extraIds = computed(() => accs.value.filter((id) => !listed.value.includes(id)))
function addManual() {
  const v = manual.value.trim()
  manualErr.value = v ? checkAccountId(v) : 'Nhập ID tài khoản quảng cáo'
  if (manualErr.value) return
  const id = cleanAccountId(v)
  if (!accs.value.includes(id)) accs.value = [...accs.value, id]
  manual.value = ''
}
async function saveConn() {
  if (!accs.value.length) return toast('Hãy chọn ít nhất 1 tài khoản quảng cáo', 'error')
  if (token.value.trim() && checkToken(token.value)) return toast(checkToken(token.value), 'error')
  const wasMock = s.value.mock
  const body = { adAccountIds: accs.value, mock: false, dryRun: wasMock ? true : s.value.dryRun }
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
          <div><dt>Tài khoản QC</dt><dd>
            <ul v-if="conn.accounts && conn.accounts.length > 1" class="acclist">
              <li v-for="a in conn.accounts" :key="a.id"><span class="an" :title="'ID ' + a.id">{{ a.name }}</span><small class="faint">{{ a.currency }}</small>
                <Badge :tone="a.error ? 'danger' : a.active ? 'success' : 'warning'" dot :title="a.error || ''">{{ a.error ? 'Lỗi' : a.status }}</Badge></li>
            </ul>
            <template v-else>{{ conn.name }} · {{ conn.currency }} <Badge :tone="conn.accountActive ? 'success' : 'warning'" dot>{{ conn.status }}</Badge></template>
          </dd></div>
          <div v-if="tk"><dt>Quyền truy cập</dt><dd><template v-if="tk.missing && tk.missing.length"><Badge tone="danger">Thiếu {{ tk.missing.join(', ') }}</Badge></template><template v-else>ads_management, ads_read <ShieldCheck :size="15" class="okc" /></template></dd></div>
          <div v-if="tk"><dt>Access Token</dt><dd><Badge :tone="tokenTone === 'ok' ? 'success' : tokenTone === 'warn' ? 'warning' : 'danger'">{{ tokenText }}</Badge></dd></div>
        </dl>
      </div>
      <div class="btns"><Btn :icon="RefreshCw" :action="recheck">Kiểm tra lại</Btn><Btn :icon="ListPlus" :action="openAccounts">Thêm / bớt tài khoản</Btn><Btn @click="openWiz">Đổi token</Btn></div>
    </section>

    <!-- Trình hướng dẫn -->
    <Transition name="wz">
      <section v-if="wizOpen" ref="wiz" class="card wz">
        <header><span class="hi"><KeyRound :size="20" /></span><h3>Kết nối Facebook</h3><Btn variant="ghost" size="sm" :icon="X" aria-label="Đóng" @click="closeWiz" /></header>

        <div class="step">
          <span class="no">1</span>
          <div class="body">
            <h4>Lấy Access Token <InfoTip tip="token" /></h4>
            <p class="muted">Token là “chìa khoá” cho phép tool điều khiển quảng cáo của bạn. Chọn cách phù hợp:</p>
            <Segmented v-model="method" :options="[{ value: 'login', label: 'Đăng nhập Facebook (60 ngày)' }, { value: 'fast', label: 'Dán token (60 ngày)' }, { value: 'stable', label: 'Người dùng hệ thống (không hết hạn)' }]" />
            <template v-if="method === 'login'">
              <ol>
                <li><div>Tạo một ứng dụng Meta loại <b>Business</b> (đã có thì bỏ qua bước này).<a class="ext" href="https://developers.facebook.com/apps/creation/" target="_blank" rel="noopener">Tạo ứng dụng mới <ExternalLink :size="13" /></a></div></li>
                <li><div>Trong ứng dụng, thêm sản phẩm <b>Facebook Login</b> (hoặc <b>Facebook Login for Business</b>) → <b>Cài đặt</b> → dán địa chỉ dưới đây vào ô <b>URI chuyển hướng OAuth hợp lệ</b> (Valid OAuth Redirect URIs) rồi Lưu.
                  <div class="uri"><code>{{ oauth.redirectUri }}</code><Btn size="sm" variant="ghost" :icon="Copy" aria-label="Sao chép" @click="copyUri" /></div>
                  <small class="faint">Facebook chỉ nhận địa chỉ <code>https://</code>, riêng <code>http://localhost</code> được phép khi ứng dụng ở chế độ phát triển.</small>
                </div></li>
                <li><div>Mở <b>Cài đặt ứng dụng → Thông tin cơ bản</b>, sao chép <b>App ID</b> và <b>App Secret</b> vào đây. Tool lưu lại để lần sau chỉ cần bấm nút.
                  <div class="row" style="margin-top: 10px">
                    <input v-model="oauth.appId" class="input" placeholder="App ID" autocomplete="off" @input="oauthErr = ''" />
                    <input v-model="oauth.secret" class="input" type="password" autocomplete="off" :placeholder="s.has_fbAppSecret && oauth.appId.trim() === s.fbAppId ? 'App Secret đã lưu (nhập để thay)' : 'App Secret'" @input="oauthErr = ''" />
                  </div>
                  <details><summary>Dùng Facebook Login for Business?</summary>
                    <div class="row"><input v-model="oauth.configId" class="input" placeholder="Configuration ID (không bắt buộc)" autocomplete="off" @input="oauthErr = ''" /></div>
                    <small class="faint">Nếu ứng dụng dùng <b>Facebook Login for Business</b>, tạo một cấu hình có quyền <code>ads_management</code>, <code>ads_read</code> và dán ID của cấu hình đó. Dùng Facebook Login thường thì để trống.</small>
                  </details>
                </div></li>
                <li><div>Bấm nút dưới, đăng nhập đúng tài khoản đang chạy quảng cáo và <b>cho phép đủ quyền</b>. Tool tự nhận token và gia hạn lên khoảng 60 ngày. Hết hạn thì bấm lại nút này.
                  <div style="margin-top: 10px"><Btn variant="primary" :icon="LogIn" :action="fbLogin">Đăng nhập bằng Facebook</Btn></div>
                  <p v-if="oauthErr" class="ferr">{{ oauthErr }}</p>
                </div></li>
              </ol>
            </template>
            <ol v-else-if="method === 'fast'">
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
            <h4>{{ method === 'login' ? 'Kiểm tra token' : 'Dán token và kiểm tra' }}</h4>
            <p v-if="method === 'login'" class="muted">{{ info ? 'Token nhận từ Facebook đã được lưu.' : 'Sau khi đăng nhập ở bước 1, tool tự kiểm tra token tại đây.' }}</p>
            <p v-else class="muted">Tool sẽ kiểm tra token còn dùng được không và có đủ quyền không.</p>
            <Btn v-if="method === 'login' && s.has_accessToken && !info" :loading="loading" :action="verify">Kiểm tra token đã lưu</Btn>
            <div v-if="method !== 'login'" class="row">
              <input v-model="token" class="input" type="password" autocomplete="off" :placeholder="s.has_accessToken ? 'Đã có token đã lưu — dán token mới hoặc bấm Kiểm tra' : 'Dán token vào đây (bắt đầu bằng EAA…)'" :class="{ bad: tokErr }" @input="tokErr = ''" @keydown.enter="verify" />
              <Btn variant="primary" :loading="loading" :action="verify">Kiểm tra token</Btn>
            </div>
            <p v-if="tokErr" class="ferr">{{ tokErr }}</p>
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

        <div ref="step3" class="step" :class="{ done: accs.length && info }">
          <span class="no"><Check v-if="accs.length && info" :size="16" /><template v-else>3</template></span>
          <div class="body">
            <h4>Chọn tài khoản quảng cáo</h4>
            <p class="muted">{{ info ? 'Chọn một hoặc nhiều tài khoản. Tool quản lý chiến dịch của tất cả tài khoản đã chọn ở cùng một nơi.' : 'Danh sách sẽ hiện ra sau khi kiểm tra token ở bước 2.' }}</p>
            <div v-if="info && info.accounts.length > 1" class="accbar">
              <label class="chk"><input type="checkbox" :checked="allAccs" :indeterminate="someAccs" @change="toggleAllAccs" /> Chọn tất cả ({{ info.accounts.length }})</label>
              <span class="faint">Đã chọn {{ accs.length }}</span>
            </div>
            <div v-if="info && info.accounts.length" class="accs">
              <label v-for="a in info.accounts" :key="a.id" class="acc" :class="{ on: accs.includes(a.id) }">
                <input type="checkbox" :checked="accs.includes(a.id)" @change="toggleAcc(a.id)" />
                <div class="x"><b>{{ a.name }}</b><small class="faint">ID {{ a.id }} · {{ a.currency }}</small></div>
                <Badge :tone="a.active ? 'success' : 'warning'" dot>{{ a.status }}</Badge>
              </label>
            </div>
            <div v-else-if="info" class="note warn"><AlertTriangle :size="18" /><div><b>Token này chưa thấy tài khoản quảng cáo nào.</b> Hãy đảm bảo tài khoản đã được gán cho người dùng/token này, hoặc nhập ID thủ công bên dưới.</div></div>
            <div v-if="extraIds.length" class="extra">
              <span v-for="id in extraIds" :key="id" class="chip">ID {{ id }}<button type="button" :aria-label="'Bỏ tài khoản ' + id" @click="toggleAcc(id)"><X :size="13" /></button></span>
            </div>
            <details><summary>Thêm ID tài khoản thủ công</summary>
              <div class="row"><input v-model="manual" class="input" placeholder="Vd: 1234567890" :class="{ bad: manualErr }" @input="manualErr = ''" @keydown.enter.prevent="addManual" /><Btn size="sm" @click="addManual">Thêm</Btn></div>
              <small class="faint">ID nằm cạnh tên tài khoản trong Ads Manager. Thêm được nhiều ID.</small>
              <p v-if="manualErr" class="ferr">{{ manualErr }}</p>
            </details>
          </div>
        </div>

        <footer><Btn @click="closeWiz">Huỷ</Btn><Btn variant="primary" :icon="Check" :disabled="!accs.length || !hasToken" :action="saveConn">Lưu & kết nối{{ accs.length > 1 ? ` ${accs.length} tài khoản` : '' }}</Btn></footer>
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
.uri { display: flex; align-items: center; gap: 6px; margin: 8px 0 4px; padding: 6px 6px 6px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.uri code { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: 13px; }
.row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; } .row .input { flex: 1; min-width: 200px; }
.note { display: flex; gap: 11px; padding: 12px 14px; border-radius: 12px; margin-top: 12px; font-size: 14.5px; }
.note > div { flex: 1; min-width: 0; } .note svg { flex: none; margin-top: 2px; } .note small { display: block; margin-top: 6px; opacity: .85; }
.note.ok { background: var(--success-soft); color: var(--success); } .note.bad { background: var(--danger-soft); color: var(--danger); } .note.warn { background: var(--warning-soft); color: var(--warning); }
.accs { display: grid; gap: 8px; margin-bottom: 12px; max-height: 380px; overflow-y: auto; padding: 2px; }
.accbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; font-size: 14px; }
.chk { display: inline-flex; align-items: center; gap: 8px; font-weight: 600; cursor: pointer; } .chk input { accent-color: var(--accent); width: 17px; height: 17px; }
.extra { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.chip { display: inline-flex; align-items: center; gap: 4px; padding: 5px 6px 5px 11px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-weight: 600; font-size: 13px; }
.chip button { border: 0; background: none; color: inherit; display: grid; place-items: center; padding: 2px; border-radius: 5px; cursor: pointer; } .chip button:hover { background: var(--accent); color: #fff; }
.acclist { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.acclist li { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; } .acclist .an { font-weight: 600; }
.acc { display: flex; align-items: center; gap: 12px; padding: 13px 15px; border: 1.5px solid var(--border); border-radius: 14px; cursor: pointer; transition: .15s; }
.acc:hover { border-color: var(--border-strong); } .acc.on { border-color: var(--accent); background: var(--accent-soft); }
.acc input { accent-color: var(--accent); width: 18px; height: 18px; flex: none; }
.x { flex: 1; min-width: 0; } .x b { display: block; } .x small { font-size: 13px; }
details { margin-top: 10px; padding: 12px 16px; border: 1px solid var(--border); border-radius: 12px; }
details summary { cursor: pointer; font-weight: 600; font-size: 14px; } details .row { margin-top: 10px; }
.wz > footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; background: var(--surface-2); }
.ferr { color: var(--danger); font-size: 13px; margin: 8px 0 0; }
.input.bad { border-color: var(--danger); box-shadow: 0 0 0 3px var(--danger-soft); }
.wz-enter-active, .wz-leave-active { transition: all .35s var(--ease); }
.wz-enter-from, .wz-leave-to { opacity: 0; transform: translateY(-10px); }
@media (max-width: 640px) { .step { flex-direction: column; gap: 10px; padding: 18px; } dt { width: 100px; } }
</style>
