<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { fileHash } from './file-transfers';
import { decodeText, encodeText, draftKey, persistDraft, restoreDraft, clearEditorDrafts, type EditorDraft } from './editor-session';
import { continueFileUpload, bindUploadWorkspace } from './file-upload-coordinator';
import type { components } from '../../shared/api/generated/http';
type Item = components['schemas']['FileItem'];
const props = defineProps<{ file: Item; userId: string; spaceId: string; workspaceId: string }>();
const emit = defineEmits<{ saved: []; close: [] }>();
const Monaco = defineAsyncComponent(() => import('./MonacoText.vue'));
const draft = ref<EditorDraft>(); const current = ref<EditorDraft>(); const busy = ref(false); const notice = ref('');
const conflict = ref(false); const readonly = ref(false); const saveAs = ref(''); const comparison = ref('current');
const comparisonReady = ref(false); const memoryOnly = ref(false);
const mobile = ref(false); const dark = ref(document.documentElement.dataset.theme === 'dark'); let controller = new AbortController(); let timer: ReturnType<typeof setTimeout> | undefined;
const key = computed(() => draftKey(props.userId, props.spaceId, props.workspaceId, props.file.id));
const storageKey = key.value;
const dirty = computed(() => Boolean(draft.value && draft.value.text !== draft.value.base));
const header = computed(() => ({ 'X-Space-Id': props.spaceId }));
async function load() {
  controller.abort(); controller = new AbortController(); const signal = controller.signal; draft.value = undefined; current.value = undefined;
  notice.value = ''; conflict.value = false; readonly.value = false;
  try {
    const meta = (await api.GET('/api/v1/files/{id}', { signal, params: { path: { id: props.file.id }, header: header.value } })).data!;
    if (!meta.versionId || meta.bytes === null || meta.bytes > 2097152) throw new Error('该文件仅支持下载');
    const raw = (await api.GET('/api/v1/files/{id}/content', { signal, parseAs: 'arrayBuffer', params: { path: { id: meta.id }, query: { versionId: meta.versionId }, header: header.value } })).data!;
    signal.throwIfAborted(); const decoded = decodeText(raw);
    current.value = { fileId: meta.id, versionId: meta.versionId, revision: meta.revision, path: meta.path, base: decoded.text, text: decoded.text, format: decoded.format };
    readonly.value = meta.collectionState !== 'CONFIRMED';
    const restored = restoreDraft(storageKey);
    const stored = restored?.fileId === meta.id ? restored : undefined;
    draft.value = stored ?? { ...current.value, format: { ...current.value.format } };
    conflict.value = Boolean(stored && (stored.versionId !== meta.versionId || stored.revision !== meta.revision)); comparisonReady.value = conflict.value;
    if (stored) notice.value = '已恢复当前浏览器会话草稿；未自动保存。';
    if (readonly.value) notice.value = '当前内容尚未封存，只读显示上次确认版本。';
    if (stored?.pending) notice.value = '已恢复原保存身份，请读取原保存状态；不会自动重新传输。';
  } catch (error) { if (!signal.aborted) notice.value = error instanceof Error ? error.message : '文件暂不可用'; }
}
function persist() {
  if (!draft.value) return;
  if (!dirty.value && !draft.value.pending) { try { sessionStorage.removeItem(storageKey); } catch { /* Storage unavailable. */ } return; }
  memoryOnly.value = !persistDraft(storageKey, draft.value);
}
watch(() => draft.value?.text, () => { clearTimeout(timer); timer = setTimeout(persist, 200); });
async function reconcile(queryOnly = false) {
  const d = draft.value; if (!d?.pending) return;
  const content = new File([encodeText(d.submitted!, d.format)], d.path);
  const result = await continueFileUpload(bindUploadWorkspace(d.pending, props.workspaceId), content, props.spaceId, controller.signal, persist, undefined, queryOnly);
  if (result?.state === 'COMMITTED') {
    const meta = d.pending.body.fileId ? (await api.GET('/api/v1/files/{id}', { signal: controller.signal, params: { path: { id: d.pending.body.fileId }, header: header.value } })).data : undefined;
    // Save-as has a new identity; refresh directory without rebasing the original buffer.
    if (d.pending.body.fileId && meta) {
      const upload = (await api.GET('/api/v1/uploads/{id}', { signal: controller.signal, params: { path: { id: d.pending.uploadId! }, header: header.value } })).data!;
      if (upload.versionId === meta.versionId) { d.base = d.submitted!; d.versionId = meta.versionId!; d.revision = meta.revision; }
      else { conflict.value = true; notice.value = '原保存完成，但服务器已有更新版本，请比较后继续。'; }
    }
    d.pending = undefined; d.submitted = undefined; persist(); if (!conflict.value) notice.value = '保存已完成，版本已封存。'; emit('saved');
  } else if (result?.state === 'FAILED') { conflict.value = result.errorCode === 'FILE_VERSION_CONFLICT'; notice.value = `保存失败：${result.errorCode}`; d.pending = undefined; d.submitted = undefined; persist(); }
  else notice.value = result?.state === 'UNKNOWN' ? '结果待核对，不会重新执行。' : '保存处理中，请读取原保存状态。';
}
async function save() {
  const d = draft.value; if (!d || busy.value) return;
  const queryOnly = Boolean(d.pending && (conflict.value || readonly.value));
  if (!d.pending && (readonly.value || conflict.value && !saveAs.value)) return;
  if (!d.pending && !dirty.value && !saveAs.value) { notice.value = '内容未变化，无需保存。'; return; }
  busy.value = true; notice.value = queryOnly ? '正在读取原保存状态…' : '正在保存，等待版本封存…';
  try {
    if (!d.pending) {
      const submitted = d.text, targetPath = saveAs.value || d.path, creating = Boolean(saveAs.value);
      const file = new File([encodeText(submitted, d.format)], targetPath);
      const hash = await fileHash(file, controller.signal); controller.signal.throwIfAborted();
      d.pending = { key: newRequestId(), body: { path: targetPath, bytes: file.size, sha256: hash,
        ...(!creating ? { fileId: d.fileId, baseVersionId: d.versionId } : {}) }, ...(!creating ? { ifMatch: `"${d.revision}"` } : {}) };
      d.submitted = submitted; persist();
    }
    await reconcile(queryOnly);
  } catch (error) {
    if (error instanceof ApiError && (error.problem.status === 412 || error.problem.status === 409 && error.problem.code === 'FILE_NAME_CONFLICT')) {
      notice.value = '目标或版本已变化，本地修改已保留。请比较或另存。'; conflict.value = true; comparisonReady.value = false;
      d.pending = undefined; d.submitted = undefined;
    } else notice.value = error instanceof ApiError && error.problem.status === 423 ? '工作区忙；本地内容保留，可稍后继续原保存。' : !d.pending && error instanceof Error ? error.message : '保存未确认，请读取原保存状态。';
    persist();
  } finally { busy.value = false; }
}
async function compare() {
  const d = draft.value; if (!d) return;
  const signal = controller.signal; comparisonReady.value = false;
  try {
  const meta = (await api.GET('/api/v1/files/{id}', { signal, params: { path: { id: d.fileId }, header: header.value } })).data!;
  if (!meta.versionId || meta.bytes === null || meta.bytes > 2097152) throw new Error('当前版本不可作为文本编辑');
  const raw = (await api.GET('/api/v1/files/{id}/content', { signal, parseAs: 'arrayBuffer', params: { path: { id: d.fileId }, query: { versionId: meta.versionId }, header: header.value } })).data!;
  signal.throwIfAborted();
  const decoded = decodeText(raw); current.value = { ...d, path: meta.path, versionId: meta.versionId!, revision: meta.revision, base: decoded.text, text: decoded.text, format: decoded.format };
  conflict.value = true; readonly.value = meta.collectionState !== 'CONFIRMED'; comparisonReady.value = !readonly.value;
  } catch { if (!signal.aborted) notice.value = '最新版本读取失败或尚未封存，本地内容已保留。'; }
}
function merge() {
  if (!draft.value || !current.value || draft.value.pending || !comparisonReady.value) return;
  draft.value.path = current.value.path; draft.value.versionId = current.value.versionId; draft.value.revision = current.value.revision; draft.value.base = current.value.base; draft.value.format = { ...current.value.format };
  conflict.value = false; persist(); notice.value = '已采用最新基线；请手动合并本地文本后保存，不会自动覆盖。';
}
function discard() {
  if (!draft.value || draft.value.pending || !confirm('放弃当前未保存修改？此操作不会修改服务器文件。')) return;
  draft.value.text = draft.value.base; persist(); notice.value = '已放弃本地修改。';
}
function leave() { persist(); return !dirty.value || confirm('保留会话草稿并离开？取消可继续编辑。'); }
function close() { if (leave()) emit('close'); }
function expired() { controller.abort(); draft.value = undefined; current.value = undefined; clearEditorDrafts(); }
function keyboard(event: KeyboardEvent) { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); void save(); } }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { persist(); event.preventDefault(); event.returnValue = ''; } }
const media = matchMedia('(max-width: 767px), (pointer: coarse)');
const theme = new MutationObserver(() => { dark.value = document.documentElement.dataset.theme === 'dark'; });
let poll: ReturnType<typeof setInterval>; let polling = false;
async function refreshState() {
  if (busy.value || polling || !draft.value) return;
  polling = true;
  try {
    if (draft.value.pending?.uploadId) await reconcile(true);
    else {
      const meta = (await api.GET('/api/v1/files/{id}', { signal: controller.signal, params: { path: { id: draft.value.fileId }, header: header.value } })).data;
      if (meta && (meta.versionId !== draft.value.versionId || meta.revision !== draft.value.revision)) {
        conflict.value = true;
        if (current.value?.versionId !== meta.versionId || current.value?.revision !== meta.revision) comparisonReady.value = false;
        notice.value = '服务器文件已变化，本地缓冲区保留，请比较。';
      }
      if (meta) readonly.value = meta.collectionState !== 'CONFIRMED';
      if (meta && draft.value.path !== meta.path) { draft.value.path = meta.path; persist(); }
      if (meta?.collectionState === 'DELETED') notice.value = '工作副本已删除；本地草稿保留，历史版本仍可查看。';
    }
  } catch { /* A transient read failure is not a failed save. */ } finally { polling = false; }
}
function resize() { mobile.value = media.matches; }
onMounted(() => { resize(); theme.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] }); poll = setInterval(refreshState, 1500); media.addEventListener('change', resize); globalThis.addEventListener('platform:session-expired', expired); globalThis.addEventListener('keydown', keyboard); globalThis.addEventListener('beforeunload', beforeUnload); });
watch(() => props.file.id, load, { immediate: true });
onBeforeRouteLeave(() => leave());
onBeforeUnmount(() => { clearInterval(poll); clearTimeout(timer); persist(); controller.abort(); theme.disconnect(); media.removeEventListener('change', resize); globalThis.removeEventListener('platform:session-expired', expired); globalThis.removeEventListener('keydown', keyboard); globalThis.removeEventListener('beforeunload', beforeUnload); });
</script>
<template>
  <section aria-label="文件文本编辑" class="editor">
    <h4>{{ draft?.path ?? file.name }} {{ dirty ? '· 未保存' : '' }}</h4>
    <p role="status">{{ notice }}</p>
    <p v-if="memoryOnly" role="alert">草稿仅当前页面保留：浏览器存储不足或超过4MiB。</p>
    <template v-if="draft">
      <div v-if="conflict"><button @click="compare">读取最新版本并比较</button><select v-model="comparison" aria-label="对比基线"><option value="current">当前服务器版本</option><option value="base">原始基线</option></select><button :disabled="Boolean(draft.pending) || !comparisonReady" @click="merge">采用最新基线，手动合并</button></div>
      <template v-if="mobile || readonly"><pre v-if="conflict">{{ comparison === 'base' ? draft.base : current?.base }}</pre><textarea v-model="draft.text" :readonly="readonly" aria-label="文件文本" spellcheck="false" /></template>
      <Monaco v-else :key="`${file.id}:${conflict}`" v-model="draft.text" :original="conflict ? comparison === 'base' ? draft.base : current?.base : undefined" :dark="dark" />
      <button :disabled="busy || readonly || conflict || Boolean(draft.pending)" @click="save">保存</button>
      <button v-if="draft.pending" :disabled="busy" @click="save">读取／继续原保存</button>
      <button :disabled="!dirty || Boolean(draft.pending)" @click="discard">放弃本地修改</button>
      <label>另存相对路径<input v-model="saveAs" :disabled="Boolean(draft.pending)" maxlength="4096"></label><button :disabled="!saveAs || busy || readonly || Boolean(draft.pending)" @click="save">另存新文件</button>
    </template>
    <button @click="close">关闭编辑</button>
  </section>
</template>
<style scoped>.editor{min-width:0;margin-top:1rem}textarea{width:100%;min-height:45vh;font-family:monospace}button,input,select{margin:.3rem;max-width:100%}pre{white-space:pre-wrap;max-height:30vh;overflow:auto}</style>
