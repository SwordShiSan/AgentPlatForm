<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import type { components } from '../../shared/api/generated/http';
import { mutationScope, persistMutation, loadMutation, readMutation, submitMutation, resolveDirectory, type PendingMutation } from './file-mutation-requests';
import FileHistoryPanel from './FileHistoryPanel.vue';

type Item = components['schemas']['FileItem'];
type Deleted = components['schemas']['DeletedFileItem'];
const props = defineProps<{ file: Item; userId: string; spaceId: string; workspaceId: string; initial: 'MOVE' | 'DELETE' | 'HISTORY' | 'RESTORE'; deleted?: Deleted }>();
const emit = defineEmits<{ close: []; changed: [] }>();
const mode = ref(props.initial); const name = ref(props.file.name); const directory = ref(props.file.parent);
const selectedVersion = ref(props.file.versionId); const pending = ref<PendingMutation>();
const posting = ref(false); const notice = ref(''); const memoryOnly = ref(false); const rejected = ref(false); let controller = new AbortController(); let completed = '';
const scope = computed(() => mutationScope(props.userId, props.spaceId, props.workspaceId, props.file.id));
function persist() { memoryOnly.value = !persistMutation(scope.value, pending.value); }
watch(scope, () => {
  controller.abort(); controller = new AbortController(); pending.value = loadMutation(scope.value); posting.value = false; notice.value = '';
  mode.value = props.initial; name.value = props.file.name; directory.value = props.file.parent; selectedVersion.value = props.file.versionId; rejected.value = false;
}, { immediate: true });
const request = useQuery({
  queryKey: computed(() => ['file-mutation-request', props.userId, props.spaceId, props.workspaceId, props.file.id, pending.value?.key]),
  enabled: computed(() => Boolean(pending.value)),
  queryFn: ({ signal }) => readMutation(pending.value!, props.spaceId, signal),
  refetchInterval: query => {
    const state = query.state.data?.operation?.state ?? query.state.data?.state;
    return state && ['COMMITTED', 'FAILED', 'UNKNOWN'].includes(state) ? false : 1500;
  },
});
const preview = useQuery({ queryKey: computed(() => ['file-mutation-preview', props.userId, props.spaceId, props.file.id, props.file.revision]),
  enabled: computed(() => mode.value === 'DELETE' && !pending.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/files/{id}/mutation-preview', { signal,
    params: { path: { id: props.file.id }, header: { 'X-Space-Id': props.spaceId } } })).data!,
});
const state = computed(() => request.data.value?.operation?.state ?? request.data.value?.state);
const knownTerminal = computed(() => state.value === 'COMMITTED' || state.value === 'FAILED');
const canContinueOriginal = computed(() => Boolean(pending.value && !pending.value.operationId && !request.data.value?.operation
  && (request.data.value && ['CREATED', 'VERIFIED'].includes(request.data.value.state) || request.error.value instanceof ApiError && request.error.value.problem.status === 404)));
const canDelete = computed(() => Boolean(preview.data.value?.allConfirmed && preview.data.value.entries <= 1000 && preview.data.value.revision === props.file.revision));
const canResetRejected = computed(() => rejected.value && request.error.value instanceof ApiError && request.error.value.problem.status === 404);
watch(request.data, value => {
  if (!pending.value || !value) return;
  if (value.operationId && !pending.value.operationId) { pending.value.operationId = value.operationId; persist(); }
  if (value.operation?.state === 'COMMITTED' && completed !== pending.value.key) { completed = pending.value.key; emit('changed'); }
});
async function submit(original = false) {
  if (posting.value) return; posting.value = true; notice.value = ''; const signal = controller.signal; const stamp = scope.value;
  rejected.value = false;
  try {
    if (!pending.value) {
      if (mode.value === 'HISTORY') return;
      if (mode.value === 'DELETE' && !canDelete.value) throw new Error('目录状态已变化或超出单次操作范围，请刷新。');
      const parent = mode.value === 'DELETE' ? null : await resolveDirectory(props.workspaceId, props.spaceId, directory.value, signal);
      signal.throwIfAborted();
      const base = { key: newRequestId(), fileId: props.file.id, ifMatch: `"${props.file.revision}"` };
      if (mode.value === 'MOVE') pending.value = { ...base, kind: 'MOVE', body: { targetParentId: parent, name: name.value } };
      else if (mode.value === 'DELETE') pending.value = { ...base, kind: 'DELETE' };
      else {
        if (props.file.kind === 'DIRECTORY' && !props.deleted) throw new Error('请选择已删除的目录记录。');
        if (props.file.kind === 'FILE' && !selectedVersion.value) throw new Error('请先选择历史版本。');
        pending.value = { ...base, kind: 'RESTORE', body: props.file.kind === 'DIRECTORY'
          ? { deletionOperationId: props.deleted!.deletionOperationId, sourceVersionId: null, targetParentId: parent, name: name.value }
          : { sourceVersionId: selectedVersion.value!, deletionOperationId: null, targetParentId: parent, name: name.value } };
      }
      persist();
    } else if (!original) { await request.refetch(); return; }
    const result = await submitMutation(pending.value, props.spaceId, signal); signal.throwIfAborted();
    if (scope.value !== stamp) return;
    pending.value.operationId = result.id; persist(); notice.value = '请求已受理，正在等待持久结果。'; await request.refetch();
  } catch (error) {
    if (!signal.aborted && scope.value === stamp) {
      notice.value = error instanceof ApiError ? `${error.problem.title}（${error.problem.code}）；原请求与输入已保留。` : error instanceof Error ? error.message : '结果未确认，原请求已保留。';
      rejected.value = error instanceof ApiError && error.problem.status < 500;
      if (pending.value) await request.refetch();
    }
  } finally { if (!signal.aborted && scope.value === stamp) posting.value = false; }
}
function chooseVersion(id: string) { if (pending.value) return; selectedVersion.value = id; mode.value = 'RESTORE'; }
function closeCompleted() { if (knownTerminal.value) { pending.value = undefined; persist(); } emit('close'); }
function resetRejected() { if (!canResetRejected.value) return; pending.value = undefined; persist(); rejected.value = false; notice.value = '原请求未受理，输入已保留；请核对刷新后的文件状态再确认。'; emit('changed'); }
onBeforeUnmount(() => controller.abort());
</script>
<template>
  <section class="lifecycle-panel" aria-label="文件生命周期">
    <h4>{{ file.path }}</h4><NButton @click="closeCompleted">关闭面板</NButton>
    <p v-if="notice" role="status">{{ notice }}</p><p v-if="memoryOnly" role="alert">浏览器存储不可用，原请求仅在当前页面保留。</p>
    <template v-if="pending">
      <p role="status">{{ state === 'COMMITTED' ? '操作已完成，版本记录已保存。' : state === 'FAILED' ? '操作明确失败；原文件与历史请以当前记录为准。' : state === 'UNKNOWN' ? '结果待核对；不会重新执行或释放未决资源。' : '操作处理中；关闭面板不会取消后台操作。' }}</p>
      <small v-if="pending.operationId">操作编号 {{ pending.operationId }}</small>
      <p v-if="request.isError.value" role="alert">暂未读取到原请求的确定结果。</p>
      <NButton :disabled="posting || request.isFetching.value" @click="request.refetch()">读取原操作状态</NButton>
      <NButton v-if="canContinueOriginal" :disabled="posting" @click="submit(true)">按原请求确认受理</NButton>
      <NButton v-if="canResetRejected" :disabled="posting" @click="resetRejected">保留输入并读取当前文件状态</NButton>
      <NButton v-if="knownTerminal" @click="closeCompleted">完成并关闭</NButton>
    </template>
    <form v-else-if="mode !== 'HISTORY'" @submit.prevent="submit()">
      <template v-if="mode === 'MOVE' || mode === 'RESTORE'">
        <p v-if="mode === 'RESTORE'">恢复会生成新当前版本并保留历史；不会默认覆盖同名文件。</p>
        <label>目标目录（留空为工作区根目录）<input v-model="directory" :disabled="posting" maxlength="4096" /></label>
        <label>名称<input v-model="name" :disabled="posting" required maxlength="255" /></label>
      </template>
      <template v-else>
        <p>删除当前工作副本，保留历史版本和已交付产物；不会释放历史对象计量。</p>
        <p v-if="preview.isFetching.value" role="status">正在核对影响范围…</p>
        <p v-if="preview.data.value">此次影响 {{ preview.data.value.entries > 1000 ? '超过1000' : preview.data.value.entries }} 项（含所选文件或目录）。</p>
        <p v-if="preview.isError.value || preview.data.value && !canDelete" role="alert">影响范围未确认、内容尚未封存或版本已变化，请刷新后选择较小范围。</p>
      </template>
      <NButton attr-type="submit" :disabled="posting || mode === 'DELETE' && !canDelete || deleted && !deleted.canRestore">
        {{ posting ? '正在受理…' : mode === 'MOVE' ? '确认移动／重命名' : mode === 'DELETE' ? '确认删除工作副本' : '确认恢复为新版本' }}
      </NButton>
    </form>
    <FileHistoryPanel v-if="file.kind === 'FILE'" :file-id="file.id" :user-id="userId" :space-id="spaceId" :disabled="Boolean(pending) || posting" @restore="chooseVersion" />
  </section>
</template>
<style scoped>
.lifecycle-panel { margin-block: 1rem; padding: 1rem; border: 1px solid currentColor; border-radius: .5rem; min-width: 0; overflow-wrap: anywhere; }
label { display: flex; flex-direction: column; gap: .35rem; margin-block: .75rem; } input { min-width: 0; max-width: 100%; padding: .5rem; }
small { display: block; overflow-wrap: anywhere; }
</style>
