<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import type { components } from '../../shared/api/generated/http';
import { textPreview } from './file-transfers';
import { readPreviewBytes, MAX_PREVIEW_OBJECT_BYTES, staticHtmlDocument } from './preview-content';
import { clearPreviewRequests, pendingPreview, previewHeaders, previewScope, savePreview, submitPreview, type PendingPreview, type PreviewRequest } from './preview-requests';

const props = defineProps<{ fileId: string; versionId: string; name: string; userId: string; spaceId: string; historical?: boolean }>();
const emit = defineEmits<{ close: [] }>();
type Preview = components['schemas']['FilePreview'];
const PdfPreview = defineAsyncComponent(() => import('./PdfPreview.vue'));
const queryClient = useQueryClient(); const id = ref<string>(); const format = ref<PreviewRequest['format']>('AUTO');
const request = shallowRef<PendingPreview>(); const creating = ref(false); const loading = ref(false); const notice = ref('');
const text = ref<string>(); const image = ref<string>(); const html = ref<string>(); const loaded = ref(''); const expired = ref(false);
let controller = new AbortController(); let contentController = new AbortController();
let retry: { key: string; revision: number } | undefined;
const scope = computed(() => previewScope(props.userId, props.spaceId, props.fileId, props.versionId, format.value));
const queryKey = computed(() => ['file-preview', props.userId, props.spaceId, id.value]);
const job = useQuery({ queryKey, enabled: computed(() => Boolean(id.value) && !expired.value), retry: false,
  queryFn: async ({ signal }) => (await api.GET('/api/v1/previews/{id}', { signal,
    params: { path: { id: id.value! }, header: { 'X-Space-Id': props.spaceId } } })).data!,
  refetchInterval: value => ['QUEUED', 'RUNNING'].includes(value.state.data?.state ?? '') ? 1500 : false,
});
const download = computed(() => `/api/v1/files/${encodeURIComponent(props.fileId)}/content?versionId=${encodeURIComponent(props.versionId)}`);
const contentUrl = computed(() => id.value ? `/api/v1/previews/${encodeURIComponent(id.value)}/content` : '');
const ready = computed(() => {
  const value = job.data.value;
  return !expired.value && !job.isError.value && value?.state === 'READY' && value.allowedActions.includes('VIEW')
    && value.fileId === props.fileId && value.versionId === props.versionId && value.bytes !== null
    && value.bytes <= MAX_PREVIEW_OBJECT_BYTES ? value : undefined;
});
const canStaticHtml = computed(() => /\.html?$/i.test(props.name) && format.value !== 'HTML_STATIC');
const label = computed(() => ({ QUEUED: '预览排队中，正在等待转换资源', RUNNING: '正在生成预览', READY: '预览已就绪',
  FAILED: '预览未能生成，原文件仍可下载', BLOCKED: '预览结果待核对，不会重复转换' })[job.data.value?.state ?? 'QUEUED']);

function clearContent() {
  contentController.abort(); contentController = new AbortController();
  if (image.value) URL.revokeObjectURL(image.value);
  text.value = undefined; image.value = undefined; html.value = undefined; loaded.value = ''; loading.value = false;
}
async function create() {
  if (creating.value || expired.value) return;
  creating.value = true; notice.value = ''; const signal = controller.signal; const selectedScope = scope.value;
  try {
    const pending = request.value ?? pendingPreview(selectedScope, { versionId: props.versionId, format: format.value }); request.value = pending;
    if (pending.previewId) { id.value = pending.previewId; return; }
    const result = await submitPreview(props.fileId, pending, props.spaceId, signal); signal.throwIfAborted();
    if (scope.value !== selectedScope) return;
    pending.previewId = result.id; savePreview(selectedScope, pending); id.value = result.id;
    queryClient.setQueryData(['file-preview', props.userId, props.spaceId, result.id], result);
  } catch (error) {
    if (!signal.aborted) notice.value = error instanceof ApiError ? `预览未完成：${error.problem.code}。原文件仍可下载。`
      : '受理结果未确认，可按原请求读取；不会创建另一份转换。';
  } finally { if (!signal.aborted) creating.value = false; }
}
watch(scope, () => {
  controller.abort(); controller = new AbortController(); clearContent(); id.value = undefined; expired.value = false; retry = undefined;
  request.value = pendingPreview(scope.value, { versionId: props.versionId, format: format.value }); creating.value = false; notice.value = '';
  void create();
}, { immediate: true });
async function loadContent(value: Preview | undefined) {
  if (!value || value.state !== 'READY' || !value.allowedActions.includes('VIEW') || expired.value) return;
  if (value.fileId !== props.fileId || value.versionId !== props.versionId || value.bytes === null || value.bytes > MAX_PREVIEW_OBJECT_BYTES) {
    clearContent(); notice.value = '预览版本身份不一致，请下载原文件。'; return;
  }
  const marker = `${value.id}:${value.revision}:${value.sha256}`;
  if (loaded.value === marker) return;
  notice.value = ''; clearContent(); loaded.value = marker;
  if (value.format === 'PDF') return;
  const signal = contentController.signal; loading.value = true;
  try {
    const bytes = await readPreviewBytes(contentUrl.value, props.spaceId, value.format === 'IMAGE' ? MAX_PREVIEW_OBJECT_BYTES : 2097152, signal);
    signal.throwIfAborted();
    if (bytes.byteLength !== value.bytes) throw new Error('预览长度与已封存版本不一致');
    if (value.format === 'IMAGE' && value.mediaType === 'image/png') image.value = URL.createObjectURL(new Blob([bytes], { type: 'image/png' }));
    else if (value.format === 'HTML_STATIC' && value.mediaType === 'text/plain') html.value = staticHtmlDocument(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    else if (value.format === 'TEXT' && value.mediaType === 'text/plain') text.value = textPreview(bytes.buffer);
    else throw new Error('此格式尚不支持内联预览');
  } catch (error) { if (!signal.aborted) notice.value = error instanceof Error ? error.message : '预览读取失败，请下载原文件。'; }
  finally { if (!signal.aborted) loading.value = false; }
}
watch(() => job.data.value, value => { void loadContent(value); }, { immediate: true });
watch(() => job.error.value, error => { if (error instanceof ApiError && [401, 403, 404].includes(error.problem.status)) clearContent(); });
async function refresh() {
  const result = await job.refetch();
  if (result.data && !result.error) { clearContent(); await loadContent(result.data); }
}
async function retryKnownFailure() {
  const value = job.data.value;
  if (creating.value || !value || value.state !== 'FAILED' || !value.allowedActions.includes('RETRY') || !value.retryable) return;
  creating.value = true; notice.value = ''; const signal = controller.signal;
  if (!retry || retry.revision !== value.revision) retry = { key: newRequestId(), revision: value.revision };
  try {
    const header = await previewHeaders(props.spaceId, signal);
    const result = await api.POST('/api/v1/previews/{id}/retry', { signal,
      params: { path: { id: value.id }, header: { ...header, 'Idempotency-Key': retry.key, 'If-Match': `"${retry.revision}"` } } });
    signal.throwIfAborted(); if (result.data) queryClient.setQueryData(queryKey.value, result.data);
  } catch (error) {
    if (!signal.aborted) { notice.value = error instanceof ApiError && error.problem.status === 412 ? '预览状态已改变，正在读取原作业。' : '重试结果未确认，请读取原作业状态。'; await job.refetch(); }
  } finally { if (!signal.aborted) creating.value = false; }
}
function sessionExpired() { expired.value = true; controller.abort(); clearContent(); clearPreviewRequests(); id.value = undefined; request.value = undefined; queryClient.removeQueries({ queryKey: ['file-preview', props.userId] }); }
onMounted(() => globalThis.addEventListener('platform:session-expired', sessionExpired));
onBeforeUnmount(() => { controller.abort(); clearContent(); globalThis.removeEventListener('platform:session-expired', sessionExpired); });
</script>
<template>
  <section class="file-preview" aria-label="固定版本文件预览">
    <header><h4>{{ name }} · 只读预览</h4><NButton @click="emit('close')">关闭预览</NButton></header>
    <p v-if="historical">当前展示明确封存的历史版本；不代表当前工作副本。</p>
    <small>版本 {{ versionId }}</small>
    <p v-if="creating || loading || job.isFetching.value && !job.data.value" role="status">正在读取预览…</p>
    <p v-else-if="job.data.value" role="status">{{ label }}</p>
    <p v-if="notice || job.isError.value" role="alert">{{ notice || '预览暂不可用，请检查连接与权限或下载原文件。' }}</p>
    <p v-if="job.data.value?.errorCode">原因编号：{{ job.data.value.errorCode }}</p>
    <div class="actions">
      <a :href="download" download>下载原文件</a>
      <NButton v-if="!id" :disabled="creating || expired" @click="create">按原请求读取</NButton>
      <NButton v-else :disabled="creating || expired || job.isFetching.value" @click="refresh">读取原作业状态</NButton>
      <NButton v-if="job.data.value?.state === 'FAILED' && job.data.value.retryable && job.data.value.allowedActions.includes('RETRY')" :disabled="creating" @click="retryKnownFailure">重试明确失败的转换</NButton>
      <NButton v-if="canStaticHtml" :disabled="creating" @click="format = 'HTML_STATIC'">显示静态 HTML 预览</NButton>
      <NButton v-if="format === 'HTML_STATIC'" :disabled="creating" @click="format = 'AUTO'">返回源码</NButton>
    </div>
    <PdfPreview v-if="ready?.format === 'PDF' && ready.bytes !== null && ready.mediaType === 'application/pdf'"
      :key="`${id}:${ready.revision}`" :url="contentUrl" :space-id="spaceId" :bytes="ready.bytes" />
    <pre v-if="text !== undefined" tabindex="0" :aria-label="historical ? '历史版本只读内容' : '文件只读内容'">{{ text || '此版本为空文本。' }}</pre>
    <img v-if="image" :src="image" :alt="`${name}的固定版本预览`">
    <iframe v-if="html" title="隔离静态 HTML 预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="html" />
    <p class="hint">预览失败不影响原文件。关闭页面仅停止读取，后台转换按原作业继续。</p>
  </section>
</template>
<style scoped>
.file-preview{border:1px solid var(--color-border, currentColor);border-radius:.65rem;padding:1rem;min-width:0;margin-block:1rem}.file-preview header,.actions{display:flex;flex-wrap:wrap;gap:.75rem;align-items:center;justify-content:space-between}.actions{justify-content:flex-start;margin-block:1rem}.actions a{color:inherit;padding:.65rem}h4,small,p{overflow-wrap:anywhere}small{display:block}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:60vh;overflow:auto;padding:1rem;border:1px solid currentColor}img{display:block;max-width:100%;height:auto;max-height:70vh;object-fit:contain}iframe{display:block;box-sizing:border-box;width:100%;height:60vh;border:1px solid currentColor;background:white}.hint{font-size:.875rem}button{min-height:44px;max-width:100%;height:auto}:deep(.n-button__content){white-space:normal;overflow-wrap:anywhere}
</style>
