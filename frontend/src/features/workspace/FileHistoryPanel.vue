<script setup lang="ts">
import { computed, ref, onBeforeUnmount, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { decodeText } from './editor-session';
const props = defineProps<{ fileId: string; userId: string; spaceId: string; disabled?: boolean }>();
const emit = defineEmits<{ restore: [string] }>();
const cursor = ref<string>(); const preview = ref(''); const previewReady = ref(false); const error = ref(''); const loading = ref(false); let controller = new AbortController();
watch(() => `${props.userId}:${props.spaceId}:${props.fileId}`, () => { controller.abort(); controller = new AbortController(); cursor.value = undefined; preview.value = ''; error.value = ''; previewReady.value = false; loading.value = false; });
const history = useQuery({ queryKey: computed(() => ['file-history', props.userId, props.spaceId, props.fileId, cursor.value]),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/files/{id}/versions', {
    signal, params: { path: { id: props.fileId }, query: { cursor: cursor.value, limit: 100 }, header: { 'X-Space-Id': props.spaceId } },
  })).data!,
});
async function open(version: string, bytes: number) {
  if (bytes > 2097152) { error.value = '此版本超过文本查看上限，请下载。'; return; }
  loading.value = true; error.value = ''; preview.value = ''; previewReady.value = false; const signal = controller.signal;
  try {
    const result = (await api.GET('/api/v1/files/{id}/content', { signal, parseAs: 'arrayBuffer',
      params: { path: { id: props.fileId }, query: { versionId: version }, header: { 'X-Space-Id': props.spaceId } } })).data!;
    signal.throwIfAborted(); if (result.byteLength > 2097152) throw new Error('此版本仅支持下载');
    preview.value = decodeText(result).text; previewReady.value = true;
  } catch { if (!signal.aborted) error.value = '无法作为文本查看，请下载原版本；当前编辑内容未改变。'; }
  finally { if (!signal.aborted) loading.value = false; }
}
onBeforeUnmount(() => controller.abort());
</script>
<template>
  <section aria-label="文件历史版本">
    <h4>历史版本</h4><p>此处只读查看固定版本；恢复会创建新版本，不改写历史。</p>
    <p v-if="history.isFetching.value || loading" role="status">正在读取版本…</p>
    <p v-if="history.isError.value" role="alert">历史暂不可用。<NButton @click="history.refetch()">重新读取</NButton></p>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul><li v-for="version in history.data.value?.items ?? []" :key="version.id">
      <p>{{ new Date(version.createdAt).toLocaleString() }} · {{ version.bytes }} B <span v-if="version.sourceVersionId">· 来自历史恢复</span></p>
      <small>版本 {{ version.id }} · SHA256 {{ version.sha256 }}</small>
      <NButton :disabled="loading || version.bytes > 2097152" @click="open(version.id, version.bytes)">只读查看</NButton>
      <a :href="`/api/v1/files/${encodeURIComponent(fileId)}/content?versionId=${encodeURIComponent(version.id)}`" download>下载此版本</a>
      <NButton :disabled="disabled" @click="emit('restore', version.id)">恢复此版本</NButton>
    </li></ul>
    <NButton v-if="history.data.value?.nextCursor" @click="cursor = history.data.value.nextCursor ?? undefined">下一页历史</NButton>
    <NButton v-if="cursor" @click="cursor = undefined">返回最新历史</NButton>
    <pre v-if="preview" aria-label="历史版本只读内容">{{ preview }}</pre>
    <p v-if="previewReady && !preview">此版本为空文本。</p>
  </section>
</template>
<style scoped>
section, li { min-width: 0; overflow-wrap: anywhere; } pre { white-space: pre-wrap; max-height: 24rem; overflow: auto; }
small { display: block; overflow-wrap: anywhere; } a { margin-inline: .5rem; }
</style>
