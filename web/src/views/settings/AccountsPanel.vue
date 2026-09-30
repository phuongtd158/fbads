<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { UserPlus, Trash2, KeyRound, Users } from 'lucide-vue-next'
import { state } from '../../stores/app'
import { api } from '../../lib/api'
import { toast, confirm } from '../../stores/ui'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'
import Modal from '../../components/Modal.vue'

// Chỉ admin thấy tab này. Mỗi tài khoản có dữ liệu riêng (token Facebook, lịch, rule, nhật ký, Telegram) và toàn quyền với
// dữ liệu của mình; admin chỉ tạo, đặt lại mật khẩu và xoá tài khoản, không xem được dữ liệu của người khác.
const users = ref([])
const f = reactive({ username: '', name: '', password: '' })
const errs = ref({})
const needPw = computed(() => !state.auth.required) // chưa đặt mật khẩu admin thì chưa tạo được tài khoản khác

async function load() { users.value = await api('users') }
onMounted(load)

async function add() {
  errs.value = {}
  try {
    await api('users', 'POST', { ...f })
  } catch (e) {
    if (e.fields) { errs.value = e.fields; return }
    throw e
  }
  toast(`Đã tạo tài khoản ${f.username.trim().toLowerCase()}. Gửi tên đăng nhập và mật khẩu cho người đó.`)
  Object.assign(f, { username: '', name: '', password: '' })
  await load()
}

// Đặt lại mật khẩu cho người quên
const reset = reactive({ open: false, user: null, password: '', err: '' })
function openReset(u) { Object.assign(reset, { open: true, user: u, password: '', err: '' }) }
async function doReset() {
  reset.err = ''
  try {
    await api(`users/${reset.user.id}/password`, 'POST', { password: reset.password })
  } catch (e) {
    if (e.fields) { reset.err = e.fields.password || e.message; return }
    throw e
  }
  toast(`Đã đặt lại mật khẩu cho ${reset.user.username}. Họ đã bị đăng xuất khỏi mọi thiết bị.`)
  reset.open = false
}

async function remove(u) {
  if (!(await confirm('Xoá tài khoản?', `${u.username} sẽ không đăng nhập được nữa. Lịch và rule của họ ngừng chạy. File dữ liệu được giữ lại trên server (đổi tên) phòng khi cần cứu.`, { ok: 'Xoá', danger: true }))) return
  await api(`users/${u.id}`, 'DELETE')
  await load()
  toast(`Đã xoá ${u.username}`)
}
const day = (iso) => (iso ? new Date(iso).toLocaleDateString('vi-VN') : '')
</script>

<template>
  <section class="card pad">
    <h3>Tài khoản</h3>
    <p class="muted sub">Mỗi tài khoản có token Facebook, lịch, rule, nhật ký và Telegram riêng, và chỉ thấy dữ liệu của mình. Chỉ admin quản lý danh sách này.</p>

    <div v-if="needPw" class="note warn"><Users :size="20" /><div>Hãy đặt mật khẩu cho admin ở <b>Cài đặt → Bảo mật</b> trước, rồi mới tạo được tài khoản cho người khác.</div></div>

    <ul v-if="users.length" class="list">
      <li v-for="u in users" :key="u.id">
        <div class="who">
          <b>{{ u.name || u.username }}</b>
          <small class="faint">{{ u.username }}<template v-if="u.admin"> · quản trị</template><template v-if="u.id === state.auth.user?.id"> · bạn</template><template v-if="u.createdAt"> · tạo {{ day(u.createdAt) }}</template></small>
        </div>
        <template v-if="!u.admin">
          <Btn size="sm" :icon="KeyRound" @click="openReset(u)">Đặt lại mật khẩu</Btn>
          <Btn size="sm" :icon="Trash2" :aria-label="'Xoá ' + u.username" :action="() => remove(u)" />
        </template>
      </li>
    </ul>

    <template v-if="!needPw">
      <h4>Tạo tài khoản</h4>
      <p class="muted hint">Tài khoản mới bắt đầu ở chế độ dùng thử với dữ liệu trống. Gửi tên đăng nhập và mật khẩu cho người đó, họ tự đổi mật khẩu ở Cài đặt → Bảo mật.</p>
      <div class="grid">
        <Field label="Tên đăng nhập" :error="errs.username"><input v-model="f.username" class="input" autocapitalize="none" spellcheck="false" placeholder="vd. lan hoặc lan@shop.vn" /></Field>
        <Field label="Tên hiển thị"><input v-model="f.name" class="input" placeholder="Không bắt buộc" /></Field>
        <Field label="Mật khẩu" :error="errs.password" hint="Ít nhất 8 ký tự"><input v-model="f.password" class="input" type="text" autocomplete="off" /></Field>
      </div>
      <Btn variant="primary" :icon="UserPlus" :action="add">Tạo tài khoản</Btn>
    </template>

    <Modal v-model="reset.open" :title="`Đặt lại mật khẩu cho ${reset.user?.username || ''}`" width="460px">
      <Field label="Mật khẩu mới" :error="reset.err" hint="Ít nhất 8 ký tự. Người đó sẽ bị đăng xuất khỏi mọi thiết bị."><input v-model="reset.password" class="input" type="text" autocomplete="off" @keydown.enter="doReset" /></Field>
      <template #footer>
        <Btn @click="reset.open = false">Huỷ</Btn>
        <Btn variant="primary" :icon="KeyRound" :action="doReset">Đặt lại</Btn>
      </template>
    </Modal>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
h4 { font-size: 15.5px; margin: 24px 0 4px; } .hint { font-size: 13.5px; margin-bottom: 14px; }
.note { display: flex; gap: 12px; padding: 13px 16px; border-radius: 14px; margin-bottom: 20px; font-size: 14.5px; align-items: flex-start; background: var(--warning-soft); color: var(--warning); } .note svg { flex: none; margin-top: 1px; }
.list { list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: 14px; }
.list li { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
.list li + li { border-top: 1px solid var(--border); }
.who { flex: 1; min-width: 0; } .who b, .who small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
@media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
</style>
