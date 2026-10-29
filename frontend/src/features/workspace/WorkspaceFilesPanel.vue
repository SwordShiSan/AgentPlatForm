<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import type { components } from '../../shared/api/generated/http';
import { fileHash, transferFile, MAX_PREVIEW_BYTES } from './file-transfers';
import { fileRequestScope, loadFileRequest, saveFileRequest, clearFileRequests, type PendingFileRequest } from './file-requests';
import FileEditor from './FileEditor.vue';
import FilePreviewPane from './FilePreviewPane.vue';
import FileTree from './FileTree.vue';
import FileLifecyclePanel from './FileLifecyclePanel.vue';
import { clearMutationRequests } from './file-mutation-requests';
import { continueFileUpload, bindUploadWorkspace } from './file-upload-coordinator';

const props = defineProps<{ workspaceId: string; userId: string; spaceId: string; storageReady: boolean }>();
type Item = components['schemas']['FileItem'];
type Deleted = components['schemas']['DeletedFileItem'];
type LifecycleMode = 'MOVE' | 'DELETE' | 'HISTORY' | 'RESTORE';
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
const preview = ref<Item>();
const editing = ref<Item>();
const lifecycle = ref<{ file: Item; initial: LifecycleMode; deleted?: Deleted }>();
const showDeleted = ref(false); const deletedCursor = ref<string>();
function manage(file: Item, initial: LifecycleMode, deleted?: Deleted) {
  if (editing.value) { error.value = '请先关闭编辑器，保留或处理未保存内容后再管理文件。'; return; }
  lifecycle.value = { file, initial, deleted };
}
function restoreDeleted(file: Deleted) {
  const slash = file.path.lastIndexOf('/');
  manage({ id: file.id, workspaceId: file.workspaceId, path: file.path, name: file.path.slice(slash + 1), parent: slash < 0 ? '' : file.path.slice(0, slash),
    kind: file.kind, versionId: file.versionId, collectionState: 'DELETED', revision: file.revision, bytes: null, sha256: null }, 'RESTORE', file);
}
function edit(item: Item) {
  if (editing.value && editing.value.id !== item.id) { error.value = '请先关闭当前编辑器，以保留或处理未保存修改。'; return; }
  editing.value = item;
}
let controller = new AbortController();
let epoch = 0;
watch(scope, value => {
  editing.value = undefined;
  lifecycle.value = undefined; showDeleted.value = false; deletedCursor.value = undefined;
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
const deleted = useQuery({ queryKey: computed(() => ['deleted-files', props.userId, props.spaceId, props.workspaceId, deletedCursor.value]),
  enabled: computed(() => props.storageReady && showDeleted.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/workspaces/{id}/deleted-files', { signal,
    params: { path: { id: props.workspaceId }, query: { cursor: deletedCursor.value, limit: 100 }, header: headers.value } })).data!,
});
function lifecycleChanged() {
  for (const key of ['workspace-files', 'file-history', 'workspace-storage', 'deleted-files']) void client.invalidateQueries({ queryKey: [key] });
}
watch(query.data, value => {
  if (!lifecycle.value || lifecycle.value.deleted) return;
  const current = value?.items.find(item => item.id === lifecycle.value?.file.id);
  if (current) lifecycle.value.file = current;
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
  editing.value = undefined;
  lifecycle.value = undefined; clearMutationRequests();
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
    await continueFileUpload(bindUploadWorkspace(pending.value, props.workspaceId), selected.value, props.spaceId, controller.signal,
      () => saveFileRequest(initialScope, pending.value), value => { if (epoch === current) progress.value = value; }); check();
    await client.invalidateQueries({ queryKey: ['file-upload'] }); await client.invalidateQueries({ queryKey: ['workspace-storage'] });
  } catch (failure) {
    if (epoch !== current) return;
    conflict.value = failure instanceof ApiError && (failure.problem.status === 412 || failure.problem.status === 409 && failure.problem.code === 'FILE_NAME_CONFLICT');
    error.value = conflict.value ? '目标已存在或版本已变化，本地所选文件已保留，未覆盖当前内容。' : failure instanceof ApiError
      ? `操作未完成：${failure.problem.code}${failure.problem.traceId ? `，诊断编号 ${failure.problem.traceId}` : ''}` : '请求尚未确认，请读取原上传状态后再操作。';
    if (pending.value?.uploadId) void upload.refetch();
  } finally { if (epoch === current) busy.value = false; }
}
function showPreview(item: Item) { error.value = ''; if (item.versionId) preview.value = item; }
function openDirectory(value: string) { parent.value = value; cursor.value = undefined; preview.value = undefined; }
function abandonDefiniteConflict() {
  if (!conflict.value || operationId.value || busy.value) return;
  pending.value = undefined; saveFileRequest(scope.value); replacement.value = undefined; conflict.value = false;
  error.value = '已结束本次明确冲突的选择；未应用的暂存内容按上传保留规则清理。所选文件仍保留。';
}
const definiteEnd = computed(() => upload.data.value?.state === 'EXPIRED'
  || upload.data.value?.state === 'ABORTED' && operation.data.value?.state === 'FAILED');
function finishDefiniteUpload() {
  if (!definiteEnd.value || busy.value) return;
  pending.value = undefined; saveFileRequest(scope.value); replacement.value = undefined; conflict.value = false;
  phase.value = '原上传已明确结束。所选文件仍保留；新建或确认当前版本后可发起另一笔上传。';
  void client.invalidateQueries({ queryKey: ['workspace-files'] });
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
      <FileTree :workspace-id="workspaceId" :space-id="spaceId" @open="edit" />
      <p v-if="editing">管理文件前请先关闭编辑器；未保存内容可保留为当前浏览器会话草稿。</p>
      <NButton @click="showDeleted = !showDeleted">{{ showDeleted ? '收起已删除文件' : '已删除文件' }}</NButton>
      <section v-if="showDeleted" aria-label="已删除文件">
        <p>工作副本删除记录至少保留30天；历史版本继续计量。此处不提供永久清理。</p>
        <p v-if="deleted.isFetching.value" role="status">正在读取删除记录…</p>
        <p v-if="deleted.isError.value" role="alert">删除记录暂不可用。<NButton @click="deleted.refetch()">重新读取</NButton></p>
        <p v-if="deleted.data.value && !deleted.data.value.items.length">暂无已删除文件。</p>
        <ul><li v-for="item in deleted.data.value?.items ?? []" :key="item.id"><span>{{ item.path }} · {{ new Date(item.deletedAt).toLocaleString() }}</span>
          <NButton :disabled="Boolean(editing) || !item.canRestore" @click="restoreDeleted(item)">查看／恢复</NButton><span v-if="!item.canRestore">正在确认清理结果</span>
        </li></ul>
        <NButton v-if="deleted.data.value?.nextCursor" @click="deletedCursor = deleted.data.value.nextCursor ?? undefined">下一页删除记录</NButton>
        <NButton v-if="deletedCursor" @click="deletedCursor = undefined">返回第一页删除记录</NButton>
      </section>
      <nav aria-label="文件目录"><NButton @click="openDirectory('')">根目录</NButton><template v-for="(part, index) in parent.split('/').filter(Boolean)" :key="index"><span aria-hidden="true">／</span><NButton @click="openDirectory(parent.split('/').slice(0, index + 1).join('/'))">{{ part }}</NButton></template></nav>
      <p v-if="query.isError.value" role="alert">文件列表暂不可用，请检查连接和权限。<NButton @click="query.refetch()">重新读取</NButton></p>
      <p v-else-if="query.isPending.value" role="status">正在读取文件…</p>
      <p v-else-if="!query.data.value?.items.length" role="status">此目录暂无已确认文件。</p>
      <ul v-else class="file-list">
        <li v-for="item in query.data.value?.items ?? []" :key="item.id">
          <div><NButton v-if="item.kind === 'DIRECTORY'" @click="openDirectory(item.path)">{{ item.name }}／</NButton><strong v-else>{{ item.name }}</strong><p>{{ item.kind === 'DIRECTORY' ? '目录' : item.bytes === null ? '尚未封存' : `${item.collectionState !== 'CONFIRMED' ? '上次封存版本 ' : ''}${bytes(item.bytes)}` }} · {{ item.collectionState === 'CONFIRMED' ? '已确认版本' : '存在未采集内容或待核对状态' }}</p></div>
          <div v-if="item.kind === 'FILE' && item.versionId" class="file-actions"><NButton :disabled="item.bytes === null || item.bytes > MAX_PREVIEW_BYTES" @click="edit(item)">编辑文本</NButton><NButton @click="showPreview(item)">{{ item.collectionState === 'CONFIRMED' ? '只读预览' : '预览上次封存版本' }}</NButton><a :href="downloadHref(item)" download>下载已封存版本</a><NButton :disabled="busy || Boolean(pending) || item.collectionState !== 'CONFIRMED'" @click="replace(item)">替换此文件</NButton></div>
          <div class="file-actions">
            <NButton v-if="item.kind === 'FILE' && item.versionId" :disabled="Boolean(editing)" @click="manage(item, 'HISTORY')">历史版本</NButton>
            <NButton :disabled="Boolean(editing) || item.collectionState !== 'CONFIRMED'" @click="manage(item, 'MOVE')">移动／重命名</NButton>
            <NButton :disabled="Boolean(editing) || item.collectionState !== 'CONFIRMED'" @click="manage(item, 'DELETE')">删除工作副本</NButton>
          </div>
        </li>
      </ul>
      <FileLifecyclePanel v-if="lifecycle" :key="`${userId}:${spaceId}:${workspaceId}:${lifecycle.file.id}:${lifecycle.initial}`" :file="lifecycle.file" :initial="lifecycle.initial"
        :deleted="lifecycle.deleted" :workspace-id="workspaceId" :user-id="userId" :space-id="spaceId" @close="lifecycle = undefined" @changed="lifecycleChanged" />
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
        <NButton v-if="pending && definiteEnd" :disabled="busy" @click="finishDefiniteUpload">结束原上传并保留所选文件</NButton>
        <p v-if="uploadLabel" role="status">{{ uploadLabel }}</p><p v-if="operation.data.value?.errorCode">原因编号：{{ operation.data.value.errorCode }}</p>
        <progress v-if="busy" :value="progress" max="100" aria-label="文件校验或传输进度">{{ progress }}%</progress>
      </form>
      <p v-if="error" role="alert">{{ error }}</p>
      <FilePreviewPane v-if="preview?.versionId" :key="`${userId}:${spaceId}:${preview.id}:${preview.versionId}`" :file-id="preview.id" :version-id="preview.versionId" :name="preview.name" :user-id="userId" :space-id="spaceId" :historical="preview.collectionState !== 'CONFIRMED'" @close="preview = undefined" />
      <FileEditor v-if="editing" :key="`${userId}:${spaceId}:${workspaceId}:${editing.id}`" :file="editing" :user-id="userId" :space-id="spaceId" :workspace-id="workspaceId" @close="editing = undefined" @saved="client.invalidateQueries({ queryKey: ['workspace-files'] })" />
      <p>文本可编辑并保存版本；移动保留文件身份，删除保留历史，恢复生成新版本。执行环境释放后文件仍保留。</p>
    </template>
  </section>
</template>

<style scoped>
.file-panel{margin-top:1.5rem;border-top:1px solid currentColor;padding-top:1rem;min-width:0}.file-list{padding:0;list-style:none}.file-list li{display:flex;justify-content:space-between;gap:1rem;flex-wrap:wrap;padding:1rem 0;border-bottom:1px solid currentColor}.file-list strong,.file-list p,.file-upload p{overflow-wrap:anywhere}.file-actions,nav{display:flex;flex-wrap:wrap;align-items:center;gap:.75rem}.file-actions a{color:inherit;padding:.75rem;min-height:44px;box-sizing:border-box}.file-upload{display:grid;gap:1rem;margin-top:1.5rem}.file-upload label{display:grid;gap:.5rem}.file-upload input{min-width:0;max-width:100%;padding:.75rem;color:inherit;background:transparent;border:1px solid currentColor;border-radius:.5rem}.file-upload input[type=text]{width:100%;box-sizing:border-box}progress{max-width:100%}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:30rem;overflow:auto;padding:1rem;border:1px solid currentColor}button{min-height:44px}
</style>
