<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NButton, NModal } from 'naive-ui';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { ApiError } from '../../shared/api/request';
import { newRequestId as requestId } from '../../shared/api/request-id';
import type { components } from '../../shared/api/generated/http';
import WorkspaceEnvironmentPanel from './WorkspaceEnvironmentPanel.vue';
import WorkspaceFilesPanel from './WorkspaceFilesPanel.vue';

type Workspace = components['schemas']['Workspace'];
const environmentEnabled = import.meta.env.VITE_WORKSPACE_ENVIRONMENT_ENABLED === 'true';
const filesEnabled = import.meta.env.VITE_WORKSPACE_FILES_ENABLED === 'true';
const queryClient = useQueryClient();
const router = useRouter();
const route = useRoute();
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const space = computed(() => me.data.value?.spaces.find(s => s.type === 'PERSONAL')?.id);
const name = ref('');
const error = ref('');
const busy = ref(false);
const open = ref(false);
// Retain uncertain request identity and original input; do not mutate and reuse its key.
const pending = ref<{ key: string; name: string }>();
const retryKeys = new Map<string, { version: number; key: string }>();
function sessionExpired() {
  pending.value = undefined; retryKeys.clear(); name.value = ''; setCsrf(undefined); queryClient.clear();
  void router.replace('/login');
}
onMounted(() => globalThis.addEventListener('platform:session-expired', sessionExpired));
onUnmounted(() => globalThis.removeEventListener('platform:session-expired', sessionExpired));
const workspaces = useQuery({
  queryKey: computed(() => ['workspaces', me.data.value?.id, space.value]),
  enabled: computed(() => Boolean(space.value)),
  queryFn: async () => {
    const result = await api.GET('/api/v1/workspaces', { params: { header: { 'X-Space-Id': space.value } } });
    if (!result.data) throw new Error('工作区暂时不可用');
    return result.data;
  },
  refetchInterval: 2000,
});
const selectedId = computed(() => typeof route.params.id === 'string' ? route.params.id : undefined);
const detail = useQuery({
  queryKey: computed(() => ['workspaces', me.data.value?.id, space.value, selectedId.value]),
  enabled: computed(() => Boolean(space.value && selectedId.value)),
  queryFn: async () => {
    const result = await api.GET('/api/v1/workspaces/{id}', { params: { path: { id: selectedId.value! }, header: { 'X-Space-Id': space.value } } });
    if (!result.data) throw new Error('工作区不存在或无权访问');
    return result.data;
  },
  refetchInterval: 2000,
});
const visibleWorkspaces = computed(() => selectedId.value ? detail.data.value ? [detail.data.value] : [] : workspaces.data.value?.items ?? []);
async function csrf() {
  const response = await api.GET('/api/v1/auth/csrf');
  if (!response.data) throw new Error('安全验证暂时不可用');
  setCsrf(response.data.csrfToken); return response.data.csrfToken;
}
async function create() {
  if (busy.value) return;
  busy.value = true; error.value = '';
  pending.value ??= { key: requestId(), name: name.value.trim() };
  try {
    const token = await csrf();
    const result = await api.POST('/api/v1/workspaces', {
      params: { header: { 'X-CSRF-Token': token, 'Idempotency-Key': pending.value.key, 'X-Space-Id': space.value } },
      body: { name: pending.value.name },
    });
    if (!result.data) throw new Error('创建未确认，请保留原请求重试');
    pending.value = undefined; name.value = ''; open.value = false;
    await queryClient.invalidateQueries({ queryKey: ['workspaces'] });
  } catch (failure) {
    if (failure instanceof ApiError && failure.problem.status >= 400 && failure.problem.status < 500) {
      pending.value = undefined;
      error.value = `创建被拒绝：${failure.problem.code}。诊断编号：${failure.problem.traceId}`;
    } else error.value = '创建未确认。再次发送将使用原内容及请求编号，不会重复创建。';
  }
  finally { busy.value = false; }
}
async function retry(workspace: Workspace) {
  if (busy.value || workspace.state !== 'ERROR') return;
  busy.value = true; error.value = '';
  const previous = retryKeys.get(workspace.id);
  const entry = previous?.version === workspace.version ? previous : { version: workspace.version, key: requestId() };
  retryKeys.set(workspace.id, entry);
  try {
    const token = await csrf();
    const result = await api.POST('/api/v1/workspaces/{id}/retry', { params: {
      path: { id: workspace.id }, header: { 'X-CSRF-Token': token, 'Idempotency-Key': entry.key, 'If-Match': `"${entry.version}"`, 'X-Space-Id': space.value },
    } });
    if (!result.data) throw new Error('重试未确认');
    retryKeys.delete(workspace.id); await queryClient.invalidateQueries({ queryKey: ['workspaces'] });
  } catch { error.value = '重试未确认，请刷新状态后再检查。待核对操作不能重试。'; }
  finally { busy.value = false; }
}
const stateLabel = (state: Workspace['state']) => ({ PROVISIONING: '正在准备存储', READY: '存储已就绪', ERROR: '准备失败', BLOCKED: '结果待核对' })[state];
</script>

<template>
  <main id="main-content" class="workspace-page" tabindex="-1">
    <header class="workspace-heading"><div><p class="eyebrow">个人空间</p><h1>工作区</h1></div><NButton type="primary" @click="open = true">创建工作区</NButton></header>
    <p class="intro">{{ filesEnabled ? '存储就绪后，可浏览、上传、显式替换和下载确认版本。文件功能不要求启动执行环境。' : environmentEnabled ? '工作区保存独立的项目文件。存储就绪后，可在详情中准备和查看独立环境；文件与终端功能尚未开放。' : '工作区保存独立的项目文件。当前阶段仅准备存储，执行环境尚未开放。' }}</p>
    <RouterLink v-if="selectedId" to="/app/workspaces">返回工作区列表</RouterLink>
    <p v-if="me.isError.value || (selectedId ? detail.isError.value : workspaces.isError.value)" role="alert">工作区暂时不可用，请检查连接和访问权限。<NButton @click="selectedId ? detail.refetch() : workspaces.refetch()">重新读取</NButton></p>
    <p v-else-if="me.isPending.value || (selectedId ? detail.isPending.value : workspaces.isPending.value)" role="status">正在读取工作区…</p>
    <p v-else-if="!visibleWorkspaces.length" class="health-card">还没有工作区。创建后将按真实容量分配独立存储。</p>
    <ul v-else class="workspace-list">
      <li v-for="workspace in visibleWorkspaces" :key="workspace.id" class="health-card">
        <h2><RouterLink :to="`/app/workspaces/${workspace.id}`">{{ workspace.name }}</RouterLink></h2><p role="status">{{ stateLabel(workspace.state) }}</p>
        <dl><dt>磁盘额度</dt><dd>2 GiB</dd><dt>文件数额度</dt><dd>{{ workspace.quotaInodes.toLocaleString() }}</dd><dt>工作区编号</dt><dd class="workspace-id">{{ workspace.id }}</dd></dl>
        <p v-if="workspace.state === 'READY'">{{ environmentEnabled ? '存储已就绪。环境状态请在工作区详情中查看。' : '存储已就绪；执行环境尚未开放。' }}</p>
        <p v-if="workspace.state === 'BLOCKED'">结果尚未确认，资源预留仍保留。请等待核对，不要重新创建。</p>
        <p v-if="workspace.errorCode">原因编号：{{ workspace.errorCode }}</p>
        <NButton v-if="workspace.state === 'ERROR'" :disabled="busy" @click="retry(workspace)">重试准备</NButton>
        <WorkspaceEnvironmentPanel v-if="environmentEnabled && selectedId && me.data.value && space" :workspace-id="workspace.id" :user-id="me.data.value.id" :space-id="space" :storage-ready="workspace.state === 'READY'" />
        <WorkspaceFilesPanel v-if="filesEnabled && selectedId && me.data.value && space" :workspace-id="workspace.id" :user-id="me.data.value.id" :space-id="space" :storage-ready="workspace.state === 'READY'" />
      </li>
    </ul>
    <NModal v-model:show="open" preset="card" title="创建工作区" style="width:min(32rem,calc(100vw - 2rem))" :mask-closable="!pending && !busy" :close-on-esc="!pending && !busy" :closable="!pending && !busy">
      <form @submit.prevent="create"><label>名称<input v-model="name" required :disabled="busy || Boolean(pending)" maxlength="160" autocomplete="off"></label>
        <p v-if="pending">原请求尚未确认，名称和请求编号已保留。</p>
        <NButton type="primary" attr-type="submit" :disabled="busy">{{ busy ? '正在提交…' : pending ? '确认原请求' : '创建' }}</NButton>
        <NButton v-if="!pending" attr-type="button" @click="open = false">关闭</NButton>
      </form>
      <p v-if="error" role="alert">{{ error }}</p>
    </NModal>
    <p v-if="error && !open" role="alert">{{ error }}</p>
  </main>
</template>

<style scoped>
.workspace-page{max-width:64rem;margin:auto;padding:2rem 1rem}.workspace-heading{display:flex;justify-content:space-between;align-items:center;gap:1rem}.workspace-list{list-style:none;padding:0;display:grid;gap:1rem}.workspace-id{overflow-wrap:anywhere}dl{display:grid;grid-template-columns:8rem minmax(0,1fr);gap:.5rem}dd{margin:0}form{display:flex;flex-wrap:wrap;gap:1rem;align-items:end}label{display:grid;gap:.5rem;flex:1 1 15rem}input{min-width:0;width:100%;box-sizing:border-box;padding:.75rem;color:inherit;background:transparent;border:1px solid currentColor;border-radius:.5rem}button{min-height:44px}h1{margin:.25rem 0}@media(max-width:420px){.workspace-heading{align-items:start;flex-direction:column}dl{grid-template-columns:1fr}}
</style>
