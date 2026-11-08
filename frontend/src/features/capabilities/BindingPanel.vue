<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import { bindingScope, clearBindingRequests, loadBindingRequest, saveBindingRequest, type PendingBinding } from './binding-requests';
import type { components } from '../../shared/api/generated/http';

type Selection = components['schemas']['CapabilitySelection'];
const props = defineProps<{ userId: string; spaceId: string; candidate?: Selection }>();
const scope = computed(() => bindingScope(props.userId, props.spaceId));
const cache = useQueryClient(); const workspace = ref(''); const selections = ref<Selection[]>([]); const inherit = ref(false);
const dirty = ref(false); const busy = ref(false); const message = ref(''); const pending = ref<PendingBinding>();
let generation = 0;
const workspaces = useQuery({ queryKey: computed(() => ['capabilities', props.userId, props.spaceId, 'workspaces']),
  queryFn: async () => (await api.GET('/api/v1/workspaces', { params: { header: { 'X-Space-Id': props.spaceId } } })).data! });
const bindings = useQuery({ queryKey: computed(() => ['capabilities', props.userId, props.spaceId, 'bindings', workspace.value]),
  queryFn: async () => workspace.value
    ? (await api.GET('/api/v1/workspaces/{id}/capability-bindings', { params: { path: { id: workspace.value }, header: { 'X-Space-Id': props.spaceId } } })).data!
    : (await api.GET('/api/v1/me/capability-bindings', { params: { header: { 'X-Space-Id': props.spaceId } } })).data! });
const effective = useQuery({ queryKey: computed(() => ['capabilities', props.userId, props.spaceId, 'effective', workspace.value]),
  queryFn: async () => (await api.GET('/api/v1/effective-capabilities', { params: { header: { 'X-Space-Id': props.spaceId },
    query: workspace.value ? { workspaceId: workspace.value } : {} } })).data! });
watch(scope, value => { generation++; busy.value = false; pending.value = loadBindingRequest(value); workspace.value = pending.value?.workspaceId ?? ''; dirty.value = false; }, { immediate: true });
watch(workspace, () => { dirty.value = false; message.value = ''; });
watch(() => bindings.data.value, value => { if (value && !dirty.value && !pending.value) {
  selections.value = value.selections.map(item => ({ ...item })); inherit.value = value.inheritPersonal;
} }, { immediate: true });
function reset() { generation++; busy.value = false; pending.value = undefined; selections.value = []; clearBindingRequests(); }
onMounted(() => globalThis.addEventListener('platform:session-expired', reset));
onBeforeUnmount(() => { generation++; globalThis.removeEventListener('platform:session-expired', reset); });
function add() {
  if (!props.candidate || pending.value || inherit.value) return;
  if (selections.value.some(item => item.capabilityId === props.candidate!.capabilityId)) {
    message.value = '该能力已有版本，请先移除旧版本。'; return;
  }
  if (selections.value.length >= 100) { message.value = '最多选择 100 项能力。'; return; }
  selections.value = [...selections.value, { ...props.candidate }]; dirty.value = true;
}
function remove(index: number) { selections.value = selections.value.filter((_, i) => i !== index); dirty.value = true; }
async function send(request: PendingBinding) {
  busy.value = true; message.value = ''; const current = generation;
  try {
    const token = (await api.GET('/api/v1/auth/csrf')).data?.csrfToken; if (current !== generation) return; if (!token) throw new Error('安全验证暂不可用'); setCsrf(token);
    const header = { 'X-Space-Id': props.spaceId, 'X-CSRF-Token': token,
      'Idempotency-Key': request.requestId, 'If-Match': `"${request.revision}"` };
    if (request.workspaceId) await api.PUT('/api/v1/workspaces/{id}/capability-bindings', { params: { path: { id: request.workspaceId }, header }, body: request.body });
    else await api.PUT('/api/v1/me/capability-bindings', { params: { header }, body: request.body });
    if (current !== generation) return; saveBindingRequest(scope.value); pending.value = undefined; dirty.value = false;
    await cache.invalidateQueries({ queryKey: ['capabilities', props.userId, props.spaceId] }).catch(() => undefined); message.value = '能力选择已保存。';
  } catch (error) { if (current !== generation) return;
    if (error instanceof ApiError && [400, 412, 422, 428].includes(error.problem.status)) {
      saveBindingRequest(scope.value); pending.value = undefined;
      message.value = error.problem.status === 412 ? '设置已变化，当前选择已保留；请核对后重新加载。' : '选择未被接受，请检查能力是否有效。';
    } else message.value = '结果尚未确认，可以使用原请求再次确认；不会改变请求内容。';
  } finally { if (current === generation) busy.value = false; }
}
async function save() {
  if (busy.value || pending.value || !bindings.data.value) return;
  const request: PendingBinding = { requestId: newRequestId(), revision: bindings.data.value.revision,
    ...(workspace.value ? { workspaceId: workspace.value } : {}),
    body: { inheritPersonal: !!workspace.value && inherit.value, selections: inherit.value && workspace.value ? [] : selections.value.map(item => ({ ...item })) } };
  if (!saveBindingRequest(scope.value, request)) { message.value = '无法保存请求身份，请检查浏览器存储。'; return; }
  pending.value = request; await send(request);
}
async function reload() { if (pending.value || busy.value) return; dirty.value = false; await bindings.refetch(); }
</script>

<template>
  <section aria-labelledby="bindings-heading"><h2 id="bindings-heading">能力绑定</h2>
    <p>选择固定版本；工作区可继承个人选择，也可显式选择空列表。绑定不会立即在聊天中启用。</p>
    <label for="binding-workspace">配置范围</label><select id="binding-workspace" v-model="workspace" :disabled="busy || !!pending">
      <option value="">个人默认</option><option v-for="item in workspaces.data.value?.items.filter(w => w.state === 'READY')" :key="item.id" :value="item.id">{{ item.name }}</option>
    </select>
    <p v-if="bindings.isPending.value">正在读取…</p><p v-else-if="bindings.isError.value" role="alert">无法读取能力绑定。</p>
    <template v-else><label v-if="workspace"><input v-model="inherit" type="checkbox" :disabled="busy || !!pending" @change="dirty = true">继承个人选择</label>
      <ul><li v-for="(item, index) in selections" :key="item.capabilityId">{{ item.capabilityId }} / {{ item.versionId }}
        <button type="button" :disabled="busy || !!pending || inherit" @click="remove(index)">移除</button></li></ul>
      <button type="button" :disabled="busy || !!pending || inherit || !candidate" @click="add">添加当前查看的版本</button>
      <button type="button" :disabled="busy || !!pending || !dirty" @click="save">保存选择</button>
      <button type="button" :disabled="busy || !!pending" @click="reload">重新加载</button>
      <button v-if="pending" type="button" :disabled="busy" @click="send(pending)">确认原请求</button>
    </template>
    <p v-if="effective.data.value">当前来源：{{ effective.data.value.source }}；不可用版本 {{ effective.data.value.unavailable.length }} 项。</p>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
section { margin: 1rem 0; padding: 1rem; border: 1px solid var(--border-color, #888); border-radius: .5rem; }
button, select { margin: .3rem; } li { overflow-wrap: anywhere; }
</style>
