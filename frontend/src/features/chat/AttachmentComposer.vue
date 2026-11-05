<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import { fileHash, MAX_UPLOAD_BYTES, transferAttachment } from '../workspace/file-transfers';
import type { AttachmentDraft, AttachmentReference } from './attachment-drafts';
import type { components } from '../../shared/api/generated/http';

const props = defineProps<{ actor: string; space: string; modelValue: AttachmentDraft[]; disabled: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [AttachmentDraft[]]; references: [AttachmentReference[]]; processing: [boolean] }>();
const fileInput = ref<HTMLInputElement>(); const busy = ref(false); const error = ref(''); const progress = ref(0);
const choosing = ref<string>(); const files = new Map<string, File>(); let controller = new AbortController();
const query = useQuery({
  queryKey: computed(() => ['chat', props.actor, props.space, 'attachment-extractions', props.modelValue.map(i => i.extractionId ?? '').join(':')]),
  enabled: computed(() => !!props.actor && !!props.space && props.modelValue.some(i => i.extractionId)),
  queryFn: async ({ signal }) => {
    const rows: components['schemas']['DocumentExtraction'][] = [];
    for (const item of props.modelValue) if (item.extractionId) {
      const result = await api.GET('/api/v1/file-extractions/{id}', { signal, params: { path: { id: item.extractionId }, header: { 'X-Space-Id': props.space } } });
      if (result.data) rows.push(result.data);
    }
    return rows;
  },
  refetchInterval: result => result.state.data?.some(i => ['QUEUED', 'RUNNING'].includes(i.status)) ? 1000 : false,
});
const rows = computed(() => new Map(query.data.value?.map(i => [i.id, i]) ?? []));
const ready = computed<AttachmentReference[]>(() => props.modelValue.flatMap<AttachmentReference>(item => {
  if (!item.extractionId || rows.value.get(item.extractionId)?.status !== 'READY') return [];
  return item.attachmentId ? [{ kind: 'CHAT_ATTACHMENT' as const, attachmentId: item.attachmentId, extractionId: item.extractionId }]
    : item.fileId && item.versionId ? [{ kind: 'FILE_VERSION' as const, fileId: item.fileId, versionId: item.versionId, extractionId: item.extractionId }] : [];
}));
watch([ready, busy, () => props.modelValue.length], () => {
  emit('references', ready.value); emit('processing', busy.value || ready.value.length !== props.modelValue.length);
}, { immediate: true });
function update(item: AttachmentDraft) { emit('update:modelValue', props.modelValue.map(i => i.key === item.key ? { ...item } : i)); }
function add(item: AttachmentDraft) { emit('update:modelValue', [...props.modelValue, item]); }
async function csrf() {
  const token = (await api.GET('/api/v1/auth/csrf', { signal: controller.signal })).data?.csrfToken;
  if (!token) throw Error('安全验证暂不可用'); setCsrf(token); return token;
}
async function extract(item: AttachmentDraft, token: string) {
  if (item.extractionId) return;
  const body = item.attachmentId ? { attachmentId: item.attachmentId }
    : { fileId: item.fileId!, versionId: item.versionId! };
  const result = await api.POST('/api/v1/file-extractions', { signal: controller.signal, body,
    params: { header: { 'X-Space-Id': props.space, 'X-CSRF-Token': token, 'Idempotency-Key': item.extractionKey } } });
  if (!result.data) throw Error('正文提取请求尚未确认'); item.extractionId = result.data.id; update(item);
}
async function resume(item: AttachmentDraft) {
  if (props.disabled || busy.value) return; busy.value = true; error.value = '';
  try {
    const token = await csrf(); const header = { 'X-Space-Id': props.space, 'X-CSRF-Token': token };
    if (item.uploadRequest) {
      if (!item.uploadId) {
        try { item.uploadId = (await api.GET('/api/v1/attachment-uploads/requests/{id}', { signal: controller.signal, params: { path: { id: item.key }, header } })).data?.id; }
        catch (failure) { if (!(failure instanceof ApiError && failure.problem.status === 404)) throw failure; }
        if (!item.uploadId) item.uploadId = (await api.POST('/api/v1/attachment-uploads', { signal: controller.signal, body: item.uploadRequest, params: { header: { ...header, 'Idempotency-Key': item.key } } })).data?.id;
        update(item);
      }
      if (!item.uploadId) throw Error('上传身份尚未确认');
      let state = (await api.GET('/api/v1/attachment-uploads/{id}', { signal: controller.signal, params: { path: { id: item.uploadId }, header } })).data;
      if (state?.status === 'CREATED') {
        const file = files.get(item.key);
        if (!file) throw Error('请选回原文件继续传输；刷新后不保留文件字节');
        if (file.size !== item.uploadRequest.bytes || await fileHash(file, controller.signal) !== item.uploadRequest.sha256) throw Error('选回的文件与原请求不一致');
        state = await transferAttachment(item.uploadId, file, props.space, token, controller.signal, value => { progress.value = value; });
      }
      if (state?.status === 'VERIFIED') {
        item.attachmentId = (await api.POST('/api/v1/attachment-uploads/{id}/complete', { signal: controller.signal, params: { path: { id: item.uploadId }, header } })).data?.id;
        update(item);
      } else if (state?.status === 'COMMITTED') { item.attachmentId = state.attachmentId; update(item); }
      else throw Error('传输结果仍待核对，只查询原身份，不重新传输');
      files.delete(item.key);
    }
    await extract(item, token); await query.refetch();
  } catch (failure) { if (!controller.signal.aborted) error.value = failure instanceof Error ? failure.message : '附件准备未确认，原请求已保留'; }
  finally { busy.value = false; }
}
async function selected(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = '';
  if (!file || props.disabled || busy.value) return;
  if (file.size > MAX_UPLOAD_BYTES) { error.value = '附件不能超过 100 MiB'; return; }
  if (choosing.value) {
    const item = props.modelValue.find(i => i.key === choosing.value); choosing.value = undefined;
    if (item) { files.set(item.key, file); await resume({ ...item }); } return;
  }
  if (props.modelValue.length >= 10) { error.value = '每次最多 10 个附件'; return; }
  busy.value = true;
  try {
    const item: AttachmentDraft = { key: newRequestId(), extractionKey: newRequestId(), name: file.name,
      uploadRequest: { draftId: newRequestId(), filename: file.name, bytes: file.size, sha256: await fileHash(file, controller.signal) } };
    files.set(item.key, file); add(item); busy.value = false; await resume(item);
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '附件准备失败'; busy.value = false; }
}
async function addVersion(fileId: string, versionId: string, name: string) {
  if (props.disabled || busy.value || props.modelValue.length >= 10) return;
  const item = { key: newRequestId(), extractionKey: newRequestId(), name, fileId, versionId }; add(item); await resume(item);
}
function remove(item: AttachmentDraft) { if (props.disabled || busy.value) return; files.delete(item.key); emit('update:modelValue', props.modelValue.filter(i => i.key !== item.key)); }
function chooseOriginal(item: AttachmentDraft) { choosing.value = item.key; fileInput.value?.click(); }
watch(() => `${props.actor}:${props.space}`, () => { controller.abort(); controller = new AbortController(); files.clear(); });
onBeforeUnmount(() => { controller.abort(); files.clear(); });
defineExpose({ addVersion });
const labels: Record<string, string> = { QUEUED: '等待正文提取', RUNNING: '正在隔离提取正文', READY: '正文已就绪', FAILED: '正文提取失败', BLOCKED: '结果待核对' };
</script>

<template>
  <section class="attachment-composer" aria-label="聊天附件">
    <input ref="fileInput" type="file" hidden @change="selected" />
    <button type="button" :disabled="disabled || busy || modelValue.length >= 10" @click="choosing = undefined; fileInput?.click()">上传附件</button>
    <p class="intro">最多10个，每个≤100MiB。正文提取完成后发送；附件不授予文件修改或命令权限。</p>
    <ul v-if="modelValue.length">
      <li v-for="item in modelValue" :key="item.key">
        <strong>{{ item.name }}</strong><span role="status"> · {{ labels[rows.get(item.extractionId ?? '')?.status ?? ''] ?? '准备附件' }}</span>
        <span v-if="rows.get(item.extractionId ?? '')?.errorCode"> · {{ rows.get(item.extractionId ?? '')?.errorCode }}，可下载原文件。</span>
        <button type="button" :disabled="disabled || busy" @click="item.extractionId ? query.refetch() : resume({ ...item })">查询／继续</button>
        <button v-if="item.uploadRequest && !item.attachmentId" type="button" :disabled="disabled || busy" @click="chooseOriginal(item)">选回原文件</button>
        <a v-if="item.attachmentId" :href="`/api/v1/attachments/${item.attachmentId}/content`">原文件</a>
        <button type="button" :disabled="disabled || busy" @click="remove(item)">移出本次消息</button>
      </li>
    </ul>
    <p v-if="busy" role="status">正在准备附件 {{ progress ? `${progress}%` : '' }}</p>
    <p v-if="error || query.isError.value" role="alert">{{ error || '无法读取附件状态，暂不允许发送。' }}</p>
  </section>
</template>
