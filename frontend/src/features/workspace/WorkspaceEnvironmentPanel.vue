<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton, NModal } from 'naive-ui';
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
const deleteConfirmation = ref(false);
const panel = ref<HTMLElement>();
watch(scope, value => { pending.value = loadEnvironmentRequest(value); busy.value = false; error.value = ''; deleteConfirmation.value = false; }, { immediate: true });
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
const original = useQuery({
  queryKey: computed(() => ['workspace-environment-requests', props.userId, props.spaceId, props.workspaceId, pending.value?.key]),
  enabled: computed(() => Boolean(pending.value)),
  retry: false,
  refetchInterval: computed(() => pending.value ? 2000 : false),
  queryFn: async ({ signal }) => {
    const selected = pending.value;
    const selectedScope = scope.value;
    if (!selected) throw new Error('没有待确认的环境请求');
    const result = await api.GET('/api/v1/workspaces/{id}/environment/requests/{requestId}', { signal, params: {
      path: { id: props.workspaceId, requestId: selected.key }, header: { 'X-Space-Id': props.spaceId },
    } });
    if (!result.data) throw new Error('原请求尚未确认');
    return { scope: selectedScope, key: selected.key, operation: result.data };
  },
});
watch(original.data, value => {
  if (value && value.scope === scope.value && value.key === pending.value?.key) {
    pending.value = undefined; saveEnvironmentRequest(scope.value);
    void query.refetch();
  }
});
const label = (value: Environment) => ({ NONE: '尚未准备环境', PREPARING: '正在准备环境', READY: '环境已就绪', STOPPING: '正在停止并释放环境',
  STOPPED: '环境已休眠，文件保留', DELETING: '正在删除执行环境', DELETED: '环境已删除，工作区文件保留', ERROR: '环境操作失败', BLOCKED: '结果待核对' })[value.phase];
type Action = PendingEnvironmentRequest['action'];
const actions = computed(() => query.data.value?.allowedActions ?? []);
const actionLabels: Record<Action, string> = { PREPARE: '准备环境', RETRY: '重试原操作', SLEEP: '休眠环境', WAKE: '唤醒环境', DELETE: '删除环境' };
function choose(action: Action) {
  if (action === 'DELETE') deleteConfirmation.value = true;
  else void submit(action);
}
function confirmDelete() { deleteConfirmation.value = false; void submit('DELETE'); }
async function submit(action?: Action) {
  if (busy.value || query.isError.value || !props.storageReady || !query.data.value || (!pending.value && (!action || !actions.value.includes(action)))) return;
  const selectedScope = scope.value;
  const selected = pending.value ?? { key: newRequestId(), version: query.data.value.version, action: action! };
  pending.value = selected; saveEnvironmentRequest(selectedScope, selected); busy.value = true; error.value = '';
  try {
    const csrf = await api.GET('/api/v1/auth/csrf');
    if (!csrf.data) throw new Error('安全验证暂时不可用');
    if (selectedScope !== scope.value) return;
    setCsrf(csrf.data.csrfToken);
    const headers = { 'X-Space-Id': props.spaceId, 'X-CSRF-Token': csrf.data.csrfToken, 'Idempotency-Key': selected.key, 'If-Match': `"${selected.version}"` };
    const options = { params: { path: { id: props.workspaceId }, header: headers }, body: {} };
    const result = selected.action === 'DELETE'
      ? await api.DELETE('/api/v1/workspaces/{id}/environment', options)
      : selected.action === 'SLEEP'
        ? await api.POST('/api/v1/workspaces/{id}/environment/sleep', options)
        : selected.action === 'WAKE'
          ? await api.POST('/api/v1/workspaces/{id}/environment/wake', options)
          : selected.action === 'RETRY'
            ? await api.POST('/api/v1/workspaces/{id}/environment/retry', options)
            : await api.POST('/api/v1/workspaces/{id}/environment', options);
    if (!result.data) throw new Error('环境请求尚未确认');
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
    } else error.value = '请求尚未确认。正在查询原请求；再次确认时保留原请求编号、操作和版本，不会改为新的请求。';
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
      <p v-if="['STOPPING', 'DELETING'].includes(query.data.value.phase)">正在确认停止与清理；确认完成前不会分配另一个环境。</p>
      <p v-if="query.data.value.state === 'BLOCKED'">尚未确认实际结果，容量仍保留。请等待核对，不要重新创建或反复重试。</p>
      <p v-if="query.data.value.errorCode">原因编号：{{ query.data.value.errorCode }}</p>
    </template>
    <p v-if="!storageReady">请等待工作区存储就绪，再准备环境。</p>
    <p>没有活动操作后，环境将在空闲 60 秒后休眠；工作区文件和磁盘额度保留。</p>
    <p>休眠或删除环境不会删除工作区文件、历史版本和额度；临时进程和系统安装项不会保留。</p>
    <p>{{ filesEnabled ? '文件管理独立于执行环境；终端和模型工具尚未开放。' : '文件管理、终端和模型工具尚未开放。环境准备不代表这些功能已经可用。' }}</p>
    <p v-if="pending">原请求尚未确认，正在查询它的操作记录。确认原请求会使用相同编号、操作和版本。</p>
    <p v-if="original.data.value">已确认原操作：{{ original.data.value.operation.operationId }}。</p>
    <div class="environment-actions">
      <NButton v-if="pending && storageReady && !query.isError.value" type="primary" :disabled="busy || query.isPending.value || original.isFetching.value" @click="submit()">
        {{ busy ? '正在提交…' : '确认原请求' }}
      </NButton>
      <template v-else-if="storageReady && !query.isError.value">
        <NButton v-for="action in actions" :key="action" :type="action === 'DELETE' ? 'error' : 'primary'" :disabled="busy || query.isPending.value" @click="choose(action)">
          {{ busy ? '正在提交…' : actionLabels[action] }}
        </NButton>
      </template>
      <NButton :disabled="busy || query.isFetching.value" @click="query.refetch()">刷新环境状态</NButton>
    </div>
    <NModal v-model:show="deleteConfirmation" preset="dialog" title="删除执行环境？" positive-text="删除环境" negative-text="保留环境" @positive-click="confirmDelete">
      <p>只移除执行环境，保留工作区、文件、历史版本和磁盘额度。临时进程和系统安装项不会保留，之后可重新准备环境。</p>
    </NModal>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.environment-panel{margin-top:1.25rem;padding-top:1rem;border-top:1px solid currentColor}.environment-panel:focus-visible{outline:2px solid currentColor;outline-offset:4px}.environment-actions{display:flex;gap:.75rem;flex-wrap:wrap}.environment-actions button{min-height:44px}dl{display:grid;grid-template-columns:6rem minmax(0,1fr);gap:.5rem}dd{margin:0}.operation-id{overflow-wrap:anywhere}@media(max-width:480px){dl{grid-template-columns:1fr}}
</style>
