<script setup>
import { reactive, ref, onMounted } from 'vue'
import { UserPlus, Trash2, Check } from 'lucide-vue-next'
import { state, isOwner, loadAuth } from '../../stores/app'
import { api } from '../../lib/api'
import { toast, confirm } from '../../stores/ui'
import Btn from '../../components/Btn.vue'
import Field from '../../components/Field.vue'

// Thành viên của workspace đang chọn. Chỉ chủ (OWNER) được đổi tên workspace, thêm/sửa/xoá thành viên; người khác chỉ xem.
const ROLES = [
  ['OWNER', 'Chủ', 'Toàn quyền: cài đặt, token Facebook, Telegram, thành viên'],
  ['EDITOR', 'Biên tập', 'Sửa lịch, rule, bật/tắt camp, đổi ngân sách, hoàn tác'],
  ['VIEWER', 'Chỉ xem', 'Xem số liệu, lịch, rule, nhật ký; không sửa được gì'],
]
const roleLabel = (r) => (ROLES.find(([v]) => v === r) || [r, r])[1]

const members = ref([])
const wsName = ref(state.auth.workspace?.name || '')
const f = reactive({ username: '', name: '', password: '', role: 'EDITOR' })
const errs = ref({})

async function load() { members.value = await api('members') }
onMounted(load)

async function rename() {
  await api('workspace', 'POST', { name: wsName.value })
  await loadAuth()
  toast('Đã đổi tên workspace')
}

async function add() {
  errs.value = {}
  try {
    await api('members', 'POST', { ...f })
  } catch (e) {
    if (e.fields) { errs.value = e.fields; return }
    throw e
  }
  toast(`Đã thêm ${f.username}` + (f.password ? '. Gửi tên đăng nhập và mật khẩu ban đầu cho người đó.' : ''))
  Object.assign(f, { username: '', name: '', password: '', role: 'EDITOR' })
  await load()
}

async function setRole(m, role) {
  try { await api(`members/${m.userId}/role`, 'POST', { role }) } finally { await load() }
  toast(`${m.username}: ${roleLabel(role)}`)
}

async function remove(m) {
  if (!(await confirm('Gỡ thành viên?', `${m.username} sẽ không vào được workspace này nữa (tài khoản vẫn còn).`, { ok: 'Gỡ', danger: true }))) return
  await api(`members/${m.userId}`, 'DELETE')
  await load()
  toast(`Đã gỡ ${m.username}`)
}
</script>

<template>
  <section class="card pad">
    <h3>Thành viên</h3>
    <p class="muted sub">Mỗi workspace có cài đặt, token Facebook, lịch, rule và nhật ký riêng. Mời người khác cùng quản lý với vai trò phù hợp.</p>

    <div v-if="isOwner()" class="rename">
      <Field label="Tên workspace"><input v-model="wsName" class="input" maxlength="100" /></Field>
      <Btn :icon="Check" :action="rename">Đổi tên</Btn>
    </div>

    <ul class="list">
      <li v-for="m in members" :key="m.userId">
        <div class="who">
          <b>{{ m.name || m.username }}</b>
          <small class="faint">{{ m.username }}<template v-if="m.userId === state.auth.user?.id"> · bạn</template></small>
        </div>
        <select v-if="isOwner()" class="input role" :value="m.role" :aria-label="'Vai trò của ' + m.username" @change="setRole(m, $event.target.value)">
          <option v-for="[v, l] in ROLES" :key="v" :value="v">{{ l }}</option>
        </select>
        <span v-else class="badge">{{ roleLabel(m.role) }}</span>
        <Btn v-if="isOwner()" size="sm" :icon="Trash2" :aria-label="'Gỡ ' + m.username" :action="() => remove(m)" />
      </li>
    </ul>

    <template v-if="isOwner()">
      <h4>Thêm thành viên</h4>
      <p class="muted hint">Người đã có tài khoản: chỉ cần tên đăng nhập. Người mới: đặt thêm mật khẩu ban đầu, rồi gửi cho họ (họ tự đổi ở Cài đặt → Bảo mật).</p>
      <div class="grid">
        <Field label="Tên đăng nhập" :error="errs.username"><input v-model="f.username" class="input" autocapitalize="none" placeholder="vd. lan hoặc lan@shop.vn" /></Field>
        <Field label="Tên hiển thị"><input v-model="f.name" class="input" placeholder="Không bắt buộc" /></Field>
        <Field label="Mật khẩu ban đầu" :error="errs.password" hint="Chỉ cần khi tạo tài khoản mới"><input v-model="f.password" class="input" type="text" autocomplete="off" /></Field>
        <Field label="Vai trò" :error="errs.role"><select v-model="f.role" class="input"><option v-for="[v, l] in ROLES" :key="v" :value="v">{{ l }}</option></select></Field>
      </div>
      <Btn variant="primary" :icon="UserPlus" :action="add">Thêm</Btn>
    </template>

    <dl class="roles">
      <template v-for="[v, l, d] in ROLES" :key="v"><dt>{{ l }}</dt><dd>{{ d }}</dd></template>
    </dl>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
h4 { font-size: 15.5px; margin: 24px 0 4px; } .hint { font-size: 13.5px; margin-bottom: 14px; }
.rename { display: flex; gap: 12px; align-items: flex-end; margin-bottom: 8px; } .rename > :first-child { flex: 1; max-width: 360px; }
.list { list-style: none; margin: 8px 0 0; padding: 0; border: 1px solid var(--border); border-radius: 14px; }
.list li { display: flex; align-items: center; gap: 12px; padding: 12px 14px; }
.list li + li { border-top: 1px solid var(--border); }
.who { flex: 1; min-width: 0; } .who b, .who small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.role { width: 140px; }
.badge { font-size: 13px; padding: 3px 10px; border-radius: 99px; background: var(--surface-3); color: var(--text-2); }
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.roles { display: grid; grid-template-columns: auto 1fr; gap: 6px 14px; margin: 24px 0 0; padding-top: 18px; border-top: 1px solid var(--border); font-size: 13.5px; }
.roles dt { font-weight: 600; } .roles dd { margin: 0; color: var(--text-2); }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 560px) { .grid { grid-template-columns: 1fr; } .role { width: 110px; } .rename { flex-direction: column; align-items: stretch; } }
</style>
