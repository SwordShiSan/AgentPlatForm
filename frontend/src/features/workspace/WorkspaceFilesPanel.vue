<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import type { components } from '../../shared/api/generated/http';
import { fileHash, transferFile, textPreview, MAX_PREVIEW_BYTES } from './file-transfers';
import { fileRequestScope, loadFileRequest, saveFileRequest, clearFileRequests, type PendingFileRequest } from './file-requests';

const props = defineProps<{ workspaceId: string; userId: string; spaceId: string; storageReady: boolean }>();
type Item = components['schemas']['FileItem'];
const client = useQueryClient();
const scope = computed(() => fileRequestScope(props.userId, props.spaceId, props.workspaceId));
const pending = ref<PendingFileRequest>();
const selected = ref<File>();
const replacement = ref<Item>();
const path = ref('');
const parent = ref('');
const cursor = ref<string>();
const busy = ref(false);
const progress = ref(0);
const phase = ref('');
const error = ref('');
const conflict = ref(false);
const preview = ref<string>();
const previewName = ref('');
let controller = new AbortController();
let epoch = 0;
watch(scope, value => {
  epoch++; controller.abort(); controller = new AbortController(); selected.value = undefined; replacement.value = undefined;
  pending.value = loadFileRequest(value); path.value = pending.value?.body.path ?? ''; parent.value = ''; cursor.value = undefined;
  busy.value = false; error.value = ''; preview.value = undefined; conflict.value = false;
}, { immediate: true });
const headers = computed(() => ({ 'X-Space-Id': props.spaceId }));
const query = useQuery({
  queryKey: computed(() => ['workspace-files', props.userId, props.spaceId, props.workspaceId, parent.value, cursor.value]),
  enabled: computed(() => props.storageReady),
  queryFn: async ({ signal }) => {
    const result = await api.GET('/api/v1/workspaces/{id}/files', { signal, params: {
      path: { id: props.workspaceId }, query: { parent: parent.value, cursor: cursor.value, limit: 100 }, header: headers.value,
    } });
    if (!result.data) throw new Error('文件列表暂不可用'); return result.data;
  }, refetchInterval: 5000,
});
const usage = useQuery({
  queryKey: computed(() => ['workspace-storage', props.userId, props.spaceId, props.workspaceId]),
  enabled: computed(() => props.storageReady),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/workspaces/{id}/storage-usage', { signal, params: { path: { id: props.workspaceId }, header: headers.value } })).data!,
  refetchInterval: 5000,
});
const upload = useQuery({
  queryKey: computed(() => ['file-upload', props.userId, props.spaceId, pending.value?.uploadId]),
  enabled: computed(() => Boolean(pending.value?.uploadId)),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/uploads/{id}', { signal, params: { path: { id: pending.value!.uploadId! }, header: headers.value } })).data!,
  refetchInterval: 1500,
});
const operationId = computed(() => upload.data.value?.operationId ?? undefined);
const operation = useQuery({
  queryKey: computed(() => ['file-operation', props.userId, props.spaceId, operationId.value]),
  enabled: computed(() => Boolean(operationId.value)),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/file-operations/{id}', { signal, params: { path: { id: operationId.value! }, header: headers.value } })).data!,
  refetchInterval: 1500,
});
watch(operation.data, value => {
  if (pending.value && value?.state === 'COMMITTED') {
    pending.value = undefined; saveFileRequest(scope.value); selected.value = undefined; replacement.value = undefined; path.value = '';
    phase.value = '上传已完成，版本已保存';
    void client.invalidateQueries({ queryKey: ['workspace-files'] }); void client.invalidateQueries({ queryKey: ['workspace-storage'] });
  }
});
function sessionExpired() {
  epoch++; controller.abort(); clearFileRequests(); pending.value = undefined; selected.value = undefined;
  replacement.value = undefined; preview.value = undefined; path.value = ''; client.clear();
}
onMounted(() => globalThis.addEventListener('platform:session-expired', sessionExpired));
onUnmounted(() => { epoch++; controller.abort(); selected.value = undefined; globalThis.removeEventListener('platform:session-expired', sessionExpired); });
function choose(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0];
  if (selected.value && !pending.value) path.value = replacement.value?.path ?? (parent.value ? `${parent.value}/` : '') + selected.value.name.normalize('NFC');
}
function replace(item: Item) {
  if (busy.value || pending.value) { error.value = '请先读取并完成当前上传；待核对请求不能替换为新请求。'; return; }
  replacement.value = item; path.value = item.path; error.value = ''; conflict.value = false;
}
async function submit() {
  if (busy.value || !props.storageReady) return;
  busy.value = true; error.value = ''; progress.value = 0;
  const current = epoch, initialScope = scope.value;
  const check = () => { if (epoch !== current || controller.signal.aborted) throw new DOMException('身份或页面已变化', 'AbortError'); };
  try {
    if (!pending.value) {
      if (!selected.value) { error.value = '请选择要上传的文件'; return; }
      phase.value = '正在计算文件校验值';
      const hash = await fileHash(selected.value, controller.signal, value => { if (epoch === current) progress.value = value; }); check();
      pending.value = { key: newRequestId(), body: { path: path.value.normalize('NFC'), bytes: selected.value.size, sha256: hash,
        ...(replacement.value?.versionId ? { fileId: replacement.value.id, baseVersionId: replacement.value.versionId } : {}) },
        ...(replacement.value ? { ifMatch: `"${replacement.value.revision}"` } : {}) };
      saveFileRequest(initialScope, pending.value);
    }
    const token = (await api.GET('/api/v1/auth/csrf', { signal: controller.signal })).data?.csrfToken; check();
    if (!token) throw new Error('无法取得安全验证'); setCsrf(token);
    if (!pending.value.uploadId) {
      phase.value = '正在确认原上传请求';
      const result = await api.POST('/api/v1/workspaces/{id}/uploads', { signal: controller.signal, body: pending.value.body, params: {
        path: { id: props.workspaceId }, header: { ...headers.value, 'X-CSRF-Token': token, 'Idempotency-Key': pending.value.key, ...(pending.value.ifMatch ? { 'If-Match': pending.value.ifMatch } : {}) },
      } }); check();
      if (!result.data) throw new Error('上传身份未确认'); pending.value = { ...pending.value, uploadId: result.data.id }; saveFileRequest(initialScope, pending.value);
    }
    let state = (await api.GET('/api/v1/uploads/{id}', { signal: controller.signal, params: { path: { id: pending.value.uploadId! }, header: headers.value } })).data; check();
    if (state?.state === 'CREATED') {
      if (!selected.value) { phase.value = '请重新选择同一文件后继续原上传'; return; }
      const hash = await fileHash(selected.value, controller.signal); check();
      if (selected.value.size !== pending.value.body.bytes || hash !== pending.value.body.sha256) {
        error.value = '所选文件与原请求内容不同，不能复用原上传编号'; return;
      }
      phase.value = '正在传输和校验文件'; progress.value = 0;
      state = await transferFile(state.id, selected.value, props.spaceId, token, controller.signal, value => { if (epoch === current) progress.value = value; }); check();
    }
    if (state?.state === 'VERIFIED') {
      phase.value = '正在提交文件准备作业';
      await api.POST('/api/v1/uploads/{id}/complete', { signal: controller.signal, params: { path: { id: state.id }, header: { ...headers.value, 'X-CSRF-Token': token } } }); check();
    } else if (state && !['QUEUED', 'COMMITTED'].includes(state.state)) {
      phase.value = '原上传结果待核对，请读取状态；不会重新发送内容';
    }
    await client.invalidateQueries({ queryKey: ['file-upload'] }); await client.invalidateQueries({ queryKey: ['workspace-storage'] });
  } catch (failure) {
    if (epoch !== current) return;
    conflict.value = failure instanceof ApiError && failure.problem.status === 412;
    error.value = conflict.value ? '文件已有新版本，本地所选文件已保留，未覆盖当前内容。' : failure instanceof ApiError
      ? `操作未完成：${failure.problem.code}${failure.problem.traceId ? `，诊断编号 ${failure.problem.traceId}` : ''}` : '请求尚未确认，请读取原上传状态后再操作。';
    if (pending.value?.uploadId) void upload.refetch();
  } finally { if (epoch === current) busy.value = false; }
}
async function showPreview(item: Item) {
  error.value = ''; preview.value = undefined;
  if (item.bytes === null || item.bytes > MAX_PREVIEW_BYTES || !item.versionId) { error.value = '该文件请下载查看；只读文本预览上限为 2 MiB。'; return; }
  const current = epoch;
  try {
    const result = await api.GET('/api/v1/files/{id}/content', { signal: controller.signal, parseAs: 'arrayBuffer', params: { path: { id: item.id }, query: { versionId: item.versionId }, header: headers.value } });
    if (current !== epoch || !result.data) return; preview.value = textPreview(result.data); previewName.value = item.name;
  } catch { if (current === epoch) error.value = '无法作为 UTF-8 文本预览，请下载或检查权限。'; }
}
function openDirectory(value: string) { parent.value = value; cursor.value = undefined; preview.value = undefined; }
function abandonDefiniteConflict() {
  if (!conflict.value || operationId.value || busy.value) return;
  pending.value = undefined; saveFileRequest(scope.value); replacement.value = undefined; conflict.value = false;
  error.value = '已结束本次明确冲突的选择；未应用的暂存内容按上传保留规则清理。所选文件仍保留。';
}
const uploadLabel = computed(() => operation.data.value ? ({ PENDING: '文件作业排队中', CLAIMED: '已领取文件作业', RUNNING: '正在准备并保存版本', COMMITTED: '已完成', FAILED: '文件作业失败', UNKNOWN: '结果待核对，不会重做' })[operation.data.value.state]
  : upload.data.value ? ({ CREATED: '等待上传内容', UPLOADING: '正在传输', VERIFYING: '传输结果待核对', VERIFIED: '内容已校验，等待完成', QUEUED: '文件作业排队中', COMMITTED: '已完成', ABORTED: '上传已结束', EXPIRED: '上传已过期', UNKNOWN: '结果待核对' })[upload.data.value.state] : phase.value);
const canContinue = computed(() => !upload.data.value || ['CREATED', 'VERIFIED'].includes(upload.data.value.state));
const bytes = (value: number) => `${(value / 1048576).toFixed(2)} MiB`;
const downloadHref = (item: Item) => `/api/v1/files/${encodeURIComponent(item.id)}/content?versionId=${encodeURIComponent(item.versionId ?? '')}`;
function readOriginalState() { if (pending.value?.uploadId) void upload.refetch(); if (operationId.value) void operation.refetch(); }
</script>

<template>
  <section class="file-panel" aria-labelledby="workspace-files-title">
    <h3 id="workspace-files-title">工作区文件</h3>
    <p>文件可独立于执行环境使用。上传会保存不可变版本；替换需要确认当前版本，不会自动覆盖。</p>
    <p v-if="!storageReady" role="status">存储就绪后可使用文件功能。</p>
    <template v-else>
      <p v-if="usage.data.value">对象已用 {{ bytes(usage.data.value.committedBytes) }}／{{ bytes(usage.data.value.objectLimitBytes) }}，预留 {{ bytes(usage.data.value.reservedBytes) }}。工作区硬额度 {{ bytes(usage.data.value.workspaceQuotaBytes) }}／{{ usage.data.value.workspaceQuotaInodes.toLocaleString() }} inode。<span v-if="usage.data.value.measuredBytes === null">实际工作区用量尚未采样。</span></p>
      <nav aria-label="文件目录"><NButton @click="openDirectory('')">根目录</NButton><template v-for="(part, index) in parent.split('/').filter(Boolean)" :key="index"><span aria-hidden="true">／</span><NButton @click="openDirectory(parent.split('/').slice(0, index + 1).join('/'))">{{ part }}</NButton></template></nav>
      <p v-if="query.isError.value" role="alert">文件列表暂不可用，请检查连接和权限。<NButton @click="query.refetch()">重新读取</NButton></p>
      <p v-else-if="query.isPending.value" role="status">正在读取文件…</p>
      <p v-else-if="!query.data.value?.items.length" role="status">此目录暂无已确认文件。</p>
      <ul v-else class="file-list">
        <li v-for="item in query.data.value?.items ?? []" :key="item.id">
          <div><NButton v-if="item.kind === 'DIRECTORY'" @click="openDirectory(item.path)">{{ item.name }}／</NButton><strong v-else>{{ item.name }}</strong><p>{{ item.kind === 'DIRECTORY' ? '目录' : item.bytes === null ? '尚未封存' : bytes(item.bytes) }} · {{ item.collectionState === 'CONFIRMED' ? '已确认版本' : '存在未采集内容或待核对状态' }}</p></div>
          <div v-if="item.kind === 'FILE' && item.versionId" class="file-actions"><NButton @click="showPreview(item)">只读预览</NButton><a :href="downloadHref(item)" download>下载确认版本</a><NButton :disabled="busy || Boolean(pending) || item.collectionState !== 'CONFIRMED'" @click="replace(item)">替换此文件</NButton></div>
        </li>
      </ul>
      <NButton v-if="query.data.value?.nextCursor" @click="cursor = query.data.value.nextCursor ?? undefined">下一页</NButton>
      <NButton v-if="cursor" @click="cursor = undefined">返回第一页</NButton>
      <form class="file-upload" @submit.prevent="submit">
        <h4>{{ replacement || pending?.body.fileId ? '显式替换文件' : '上传新文件' }}</h4>
        <p v-if="replacement">目标：{{ replacement.path }}；当前版本 {{ replacement.versionId }}。旧版本将继续保留。</p>
        <label>选择文件（最多 100 MiB）<input type="file" :disabled="busy" @change="choose"></label>
        <label>相对文件路径<input v-model="path" required maxlength="4096" :disabled="busy || Boolean(pending) || Boolean(replacement)" autocomplete="off"></label>
        <p v-if="pending">原请求编号 {{ pending.key }} 已保留。刷新后请重新选择相同内容，不能修改内容并复用该编号。</p>
        <NButton attr-type="submit" type="primary" :disabled="busy || !canContinue">{{ busy ? '处理中…' : pending ? '继续原上传／确认完成' : replacement ? '确认替换并上传' : '上传新文件' }}</NButton>
        <NButton v-if="pending?.uploadId" :disabled="busy" @click="readOriginalState">读取原状态</NButton>
        <NButton v-if="conflict && !operationId" :disabled="busy" @click="abandonDefiniteConflict">结束这次明确冲突的选择</NButton>
        <p v-if="uploadLabel" role="status">{{ uploadLabel }}</p><p v-if="operation.data.value?.errorCode">原因编号：{{ operation.data.value.errorCode }}</p>
        <progress v-if="busy" :value="progress" max="100" aria-label="文件校验或传输进度">{{ progress }}%</progress>
      </form>
      <p v-if="error" role="alert">{{ error }}</p>
      <section v-if="preview !== undefined" aria-label="只读文本预览"><h4>{{ previewName }} · 只读</h4><NButton @click="preview = undefined">关闭预览</NButton><pre tabindex="0">{{ preview }}</pre></section>
      <p>当前不提供文本编辑、移动、删除或历史恢复；执行环境释放后文件仍保留。</p>
    </template>
  </section>
</template>

<style scoped>
.file-panel{margin-top:1.5rem;border-top:1px solid currentColor;padding-top:1rem;min-width:0}.file-list{padding:0;list-style:none}.file-list li{display:flex;justify-content:space-between;gap:1rem;flex-wrap:wrap;padding:1rem 0;border-bottom:1px solid currentColor}.file-list strong,.file-list p,.file-upload p{overflow-wrap:anywhere}.file-actions,nav{display:flex;flex-wrap:wrap;align-items:center;gap:.75rem}.file-actions a{color:inherit;padding:.75rem;min-height:44px;box-sizing:border-box}.file-upload{display:grid;gap:1rem;margin-top:1.5rem}.file-upload label{display:grid;gap:.5rem}.file-upload input{min-width:0;max-width:100%;padding:.75rem;color:inherit;background:transparent;border:1px solid currentColor;border-radius:.5rem}.file-upload input[type=text]{width:100%;box-sizing:border-box}progress{max-width:100%}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:30rem;overflow:auto;padding:1rem;border:1px solid currentColor}button{min-height:44px}
</style>
