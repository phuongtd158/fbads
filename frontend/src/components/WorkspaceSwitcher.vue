<script setup>
import { ref } from 'vue'
import { Building2, Plus } from 'lucide-vue-next'
import { state, switchWorkspace, createWorkspace } from '../stores/app'
import { toast, toastError } from '../stores/ui'
import Modal from './Modal.vue'
import Btn from './Btn.vue'
import Field from './Field.vue'

// Workspace đang làm việc + vai trò. Có nhiều workspace thì chọn để chuyển; tạo workspace mới (vd. cho khách hàng khác).
const ROLE = { OWNER: 'Chủ', EDITOR: 'Biên tập', VIEWER: 'Chỉ xem' }
const open = ref(false)
const name = ref('')

async function onPick(e) {
  const v = e.target.value
  if (v === 'new') { e.target.value = String(state.auth.workspace?.id); open.value = true; return }
  try { await switchWorkspace(Number(v)); toast('Đã chuyển sang ' + state.auth.workspace?.name) } catch (err) { toastError(err) }
}

async function create() {
  if (!name.value.trim()) return
  await createWorkspace(name.value.trim())
  open.value = false
  name.value = ''
  toast('Đã tạo workspace ' + state.auth.workspace?.name + '. Hãy kết nối Facebook cho workspace này ở Cài đặt.')
}
</script>

<template>
  <div class="ws">
    <Building2 :size="16" />
    <select class="pick" :value="state.auth.workspace?.id" aria-label="Chọn workspace" @change="onPick">
      <option v-for="w in state.auth.workspaces" :key="w.id" :value="w.id">{{ w.name }}</option>
      <option value="new">＋ Tạo workspace mới…</option>
    </select>
    <em>{{ ROLE[state.auth.workspace?.role] || '' }}</em>
  </div>
  <Modal v-model="open" title="Workspace mới" subtitle="Cài đặt, token Facebook, lịch, rule và nhật ký riêng. Bạn là chủ." width="440px">
    <form @submit.prevent="create">
      <Field label="Tên workspace"><input v-model="name" class="input" maxlength="100" placeholder="vd. Shop Lan, Khách hàng B" autofocus /></Field>
    </form>
    <template #footer><Btn @click="open = false">Huỷ</Btn><Btn variant="primary" :action="create">Tạo</Btn></template>
  </Modal>
</template>

<style scoped>
.ws { display: flex; align-items: center; gap: 8px; margin: 0 2px 12px; padding: 7px 10px; border-radius: 12px; border: 1px solid var(--border); background: var(--surface-2); color: var(--text-2); }
.pick { flex: 1; min-width: 0; border: 0; background: none; color: var(--text); font: inherit; font-weight: 600; font-size: 14px; padding: 2px 0; text-overflow: ellipsis; cursor: pointer; }
.pick:focus { outline: none; }
em { font-style: normal; font-size: 11.5px; padding: 1px 8px; border-radius: 99px; background: var(--surface-3); color: var(--text-2); white-space: nowrap; }
</style>
