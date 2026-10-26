<script setup lang="ts">
import { computed, ref } from 'vue';
import { useQuery, useInfiniteQuery } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { setCsrf } from '../../shared/api/session';
import { newRequestId } from '../../shared/api/request-id';
import type { components } from '../../shared/api/generated/http';

const props = defineProps<{ actor: string; space: string; taskId: string; taskVersion: number }>();
type Mode = 'RISK_BASED' | 'FULL'; type Capability = 'LIST' | 'READ' | 'WRITE';
type Command = { key: string; version: number; mode?: Mode; scopeId?: string; capabilities?: Capability[]; grantId?: string };
const pending = ref<Command>(); const busy = ref(false); const error = ref(''); const selected = ref('');
const capabilities = ref<Capability[]>(['READ']);
const permissions = useQuery({ queryKey: computed(() => ['chat', props.actor, props.space, 'permissions', props.taskId, props.taskVersion]),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/tasks/{id}/permissions', { signal, params: { path: { id: props.taskId }, header: { 'X-Space-Id': props.space } } })).data!,
  refetchInterval: query => query.state.data?.mutable && !(query.state.error instanceof ApiError) ? 5000 : false });
const scopes = useInfiniteQuery<components['schemas']['TaskPermissionScopes']>({ queryKey: computed(() => ['chat', props.actor, props.space, 'permission-scopes', props.taskId, permissions.data.value?.version]),
  enabled: computed(() => !!permissions.data.value?.mutable && !!permissions.data.value.workspaceId),
  initialPageParam: null as string | null,
  getNextPageParam: last => last.nextCursor ?? undefined,
  queryFn: async ({ signal, pageParam }) => (await api.GET('/api/v1/tasks/{id}/permission-scopes', { signal, params: { path: { id: props.taskId }, header: { 'X-Space-Id': props.space }, query: typeof pageParam === 'string' ? { cursor: pageParam } : {} } })).data! });
const current = computed(() => permissions.data.value);
async function send(command?: Omit<Command, 'key' | 'version'>) {
  if (busy.value || !current.value) return;
  pending.value ??= { ...command, key: newRequestId(), version: current.value.version };
  const value = pending.value; busy.value = true; error.value = '';
  try {
    const token = (await api.GET('/api/v1/auth/csrf')).data!.csrfToken; setCsrf(token);
    const params = { path: { id: props.taskId }, header: { 'X-Space-Id': props.space, 'Idempotency-Key': value.key, 'If-Match': `"${value.version}"`, 'X-CSRF-Token': token } };
    if (value.mode) await api.PATCH('/api/v1/tasks/{id}/permissions', { params, body: { mode: value.mode } });
    else if (value.grantId) await api.DELETE('/api/v1/tasks/{id}/grants/{grantId}', { params: { ...params, path: { id: props.taskId, grantId: value.grantId } } });
    else await api.POST('/api/v1/tasks/{id}/grants', { params, body: { scopeId: value.scopeId!, capabilities: value.capabilities! } });
    pending.value = undefined;
  } catch (failure) {
    if (failure instanceof ApiError && [400, 403, 404, 409, 412, 428].includes(failure.problem.status)) {
      pending.value = undefined; error.value = '权限或版本已改变，请核对最新状态后重新选择。';
    } else error.value = '变更结果尚未确认；可查询最新状态，或重试同一请求。';
  } finally { busy.value = false; await permissions.refetch(); }
}
function grant() { if (selected.value && capabilities.value.length) void send({ scopeId: selected.value, capabilities: [...capabilities.value] }); }
function label(value: components['schemas']['TaskDirectoryGrant']) { return value.active ? '有效' : value.revokedAt ? '已撤销' : '已到期或不再适用'; }
</script>

<template>
  <section class="interaction-card" aria-label="任务权限" data-testid="task-permissions">
    <h3>任务权限</h3>
    <p v-if="permissions.isPending.value" role="status">正在读取权限…</p>
    <p v-else-if="permissions.isError.value" role="alert">暂时无法读取任务权限，未扩大任何授权。<button type="button" @click="permissions.refetch()">重新读取</button></p>
    <template v-if="current">
      <p>固定工作区：{{ current.workspaceId ?? '未绑定（普通文本任务）' }}</p>
      <label>权限模式 <select :value="current.mode" :disabled="!current.mutable || busy || !!pending" @change="send({ mode: ($event.target as HTMLSelectElement).value as Mode })"><option value="RISK_BASED">按风险确认</option><option value="FULL">任务内完全权限</option></select></label>
      <p>完全权限仍受资源归属、Plan与强制策略限制。业务工具尚未开放，授权不会启动环境或执行文件操作。</p>
      <p v-if="!current.mutable">任务已结束或正在取消，权限只读，目录授权不再生效。</p>
      <fieldset v-if="current.mutable && current.workspaceId" :disabled="busy || !!pending">
        <legend>授权选定目录（本任务内，最长24小时）</legend>
        <label>目录 <select v-model="selected" aria-label="授权目录"><option value="">请选择目录</option><option v-for="scope in scopes.data.value?.pages.flatMap(page => page.items)" :key="scope.scopeId" :value="scope.scopeId">{{ scope.path || '工作区根目录' }}</option></select></label>
        <button v-if="scopes.hasNextPage.value" type="button" :disabled="scopes.isFetchingNextPage.value" @click="scopes.fetchNextPage()">更多目录</button>
        <label v-for="cap in (['LIST', 'READ', 'WRITE'] as const)" :key="cap"><input v-model="capabilities" type="checkbox" :value="cap" />{{ { LIST: '列举', READ: '读取', WRITE: '写入' }[cap] }}</label>
        <button type="button" :disabled="!selected || !capabilities.length" @click="grant">授予目录范围</button>
        <p v-if="scopes.isError.value" role="alert">目录读取失败，未创建授权。</p>
      </fieldset>
      <ul v-if="current.grants.length"><li v-for="item in current.grants" :key="item.id">{{ item.path || '工作区根目录' }} · {{ item.capabilities.join(' / ') }} · {{ label(item) }} · {{ new Date(item.expiresAt).toLocaleString() }}
        <button v-if="current.mutable && item.active" type="button" :disabled="busy || !!pending" @click="send({ grantId: item.id })">撤销授权</button>
      </li></ul>
      <p v-else>暂无目录授权。</p>
    </template>
    <p v-if="error" role="alert">{{ error }}</p><button v-if="pending" type="button" :disabled="busy" @click="send()">重试同一权限请求</button>
  </section>
</template>

<style scoped>
section { overflow-wrap: anywhere; }
fieldset { min-width: 0; display: flex; flex-wrap: wrap; gap: .75rem; }
label { max-width: 100%; }
select { max-width: 100%; }
</style>
