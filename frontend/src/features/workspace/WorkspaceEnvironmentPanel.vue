<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import type { components } from '../../shared/api/generated/http';
import { environmentRequestScope, loadEnvironmentRequest, saveEnvironmentRequest, type PendingEnvironmentRequest } from './environment-requests';

const props = defineProps<{ workspaceId: string; userId: string; spaceId: string; storageReady: boolean }>();
const filesEnabled = import.meta.env.VITE_WORKSPACE_FILES_ENABLED === 'true';
type Environment = components['schemas']['WorkspaceEnvironment'];
const queryClient = useQueryClient();
const scope = computed(() => environmentRequestScope(props.userId, props.spaceId, props.workspaceId));
const pending = ref<PendingEnvironmentRequest>();
const busy = ref(false);
const error = ref('');
const panel = ref<HTMLElement>();
watch(scope, value => { pending.value = loadEnvironmentRequest(value); busy.value = false; error.value = ''; }, { immediate: true });
const query = useQuery({
  queryKey: computed(() => ['workspace-environments', props.userId, props.spaceId, props.workspaceId]),
  queryFn: async ({ signal }) => {
    const result = await api.GET('/api/v1/workspaces/{id}/environment', { signal, params: {
      path: { id: props.workspaceId }, header: { 'X-Space-Id': props.spaceId },
    } });
    if (!result.data) throw new Error('环境状态暂时不可用');
    return result.data;
  },
  refetchInterval: 2000,
});
watch(query.data, value => {
  if (value && pending.value && value.version > pending.value.version) {
    pending.value = undefined; saveEnvironmentRequest(scope.value);
  }
});
const label = (value: Environment) => ({ NONE: '尚未准备环境', PREPARING: '正在准备环境', READY: '环境已就绪', STOPPING: '正在停止并释放环境',
  STOPPED: '环境已释放，文件保留', ERROR: '环境准备失败', BLOCKED: '结果待核对' })[value.phase];
const action = computed(() => pending.value?.action ?? query.data.value?.allowedActions[0]);
async function submit() {
  if (busy.value || query.isError.value || !props.storageReady || !action.value || !query.data.value) return;
  const selectedScope = scope.value;
  const selected = pending.value ?? { key: newRequestId(), version: query.data.value.version, action: action.value };
  pending.value = selected; saveEnvironmentRequest(selectedScope, selected); busy.value = true; error.value = '';
  try {
    const csrf = await api.GET('/api/v1/auth/csrf');
    if (!csrf.data) throw new Error('安全验证暂时不可用');
    if (selectedScope !== scope.value) return;
    setCsrf(csrf.data.csrfToken);
    const headers = { 'X-Space-Id': props.spaceId, 'X-CSRF-Token': csrf.data.csrfToken, 'Idempotency-Key': selected.key, 'If-Match': `"${selected.version}"` };
    const result = selected.action === 'RETRY'
      ? await api.POST('/api/v1/workspaces/{id}/environment/retry', { params: { path: { id: props.workspaceId }, header: headers }, body: {} })
      : await api.POST('/api/v1/workspaces/{id}/environment', { params: { path: { id: props.workspaceId }, header: headers }, body: {} });
    if (!result.data) throw new Error('准备请求尚未确认');
    if (selectedScope !== scope.value) return;
    pending.value = undefined; saveEnvironmentRequest(selectedScope);
    await queryClient.invalidateQueries({ queryKey: ['workspace-environments', props.userId, props.spaceId, props.workspaceId] });
    await queryClient.invalidateQueries({ queryKey: ['workspaces', props.userId, props.spaceId] });
    panel.value?.focus();
  } catch (failure) {
    if (selectedScope !== scope.value) return;
    if (failure instanceof ApiError && failure.problem.status >= 400 && failure.problem.status < 500) {
      pending.value = undefined; saveEnvironmentRequest(selectedScope);
      error.value = `请求被拒绝：${failure.problem.code}。诊断编号：${failure.problem.traceId}`;
      void query.refetch();
    } else error.value = '请求尚未确认。请先刷新状态；再次确认时保留原请求编号和版本，不会重新创建一套环境。';
  } finally { if (selectedScope === scope.value) busy.value = false; }
}
</script>

<template>
  <section ref="panel" class="environment-panel" :aria-labelledby="`environment-${workspaceId}`" tabindex="-1">
    <h3 :id="`environment-${workspaceId}`">执行环境</h3>
    <p v-if="query.isPending.value" role="status">正在读取环境状态…</p>
    <p v-if="query.isError.value" role="alert">无法读取最新环境状态，请检查连接与访问权限。</p>
    <template v-if="query.data.value">
      <p role="status" aria-live="polite">{{ label(query.data.value) }}</p>
      <dl><dt>环境代次</dt><dd>{{ query.data.value.generation }}</dd><dt>状态版本</dt><dd>{{ query.data.value.version }}</dd>
        <template v-if="query.data.value.operationId"><dt>操作编号</dt><dd class="operation-id">{{ query.data.value.operationId }}</dd></template></dl>
      <p v-if="query.data.value.phase === 'STOPPING'">正在确认停止；确认完成前不会分配另一个环境。</p>
      <p v-if="query.data.value.state === 'BLOCKED'">尚未确认实际结果，容量仍保留。请等待核对，不要重新创建或反复重试。</p>
      <p v-if="query.data.value.errorCode">原因编号：{{ query.data.value.errorCode }}</p>
    </template>
    <p v-if="!storageReady">请等待工作区存储就绪，再准备环境。</p>
    <p>没有活动操作后，环境将在空闲 60 秒后释放；工作区文件和磁盘额度保留。</p>
    <p>{{ filesEnabled ? '文件管理独立于执行环境；终端和模型工具尚未开放。' : '文件管理、终端和模型工具尚未开放。环境准备不代表这些功能已经可用。' }}</p>
    <p v-if="pending">原请求尚未确认，请先读取最新状态。确认原请求会使用相同编号和版本。</p>
    <div class="environment-actions">
      <NButton v-if="action && storageReady && !query.isError.value" type="primary" :disabled="busy || query.isPending.value" @click="submit">
        {{ busy ? '正在提交…' : pending ? '确认原请求' : action === 'RETRY' ? '重试准备环境' : '准备环境' }}
      </NButton>
      <NButton :disabled="busy || query.isFetching.value" @click="query.refetch()">刷新环境状态</NButton>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.environment-panel{margin-top:1.25rem;padding-top:1rem;border-top:1px solid currentColor}.environment-panel:focus-visible{outline:2px solid currentColor;outline-offset:4px}.environment-actions{display:flex;gap:.75rem;flex-wrap:wrap}.environment-actions button{min-height:44px}dl{display:grid;grid-template-columns:6rem minmax(0,1fr);gap:.5rem}dd{margin:0}.operation-id{overflow-wrap:anywhere}@media(max-width:480px){dl{grid-template-columns:1fr}}
</style>
