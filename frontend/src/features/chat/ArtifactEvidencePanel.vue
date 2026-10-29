<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { components } from '../../shared/api/generated/http';
import FilePreviewPane from '../workspace/FilePreviewPane.vue';
import { useInfiniteQuery } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
const props = defineProps<{ taskId: string; operationId: string; actor: string; space: string }>();
const preview = ref<components['schemas']['ArtifactItem']>();
watch(() => `${props.actor}:${props.space}:${props.taskId}:${props.operationId}`, () => { preview.value = undefined; });
const evidence = useInfiniteQuery({ queryKey: computed(() => ['chat', props.actor, props.space, 'task-artifacts', props.taskId]),
  initialPageParam: undefined as string | undefined,
  queryFn: async ({ signal, pageParam }) => (await api.GET('/api/v1/tasks/{id}/artifacts', {
    signal, params: { path: { id: props.taskId }, query: { cursor: pageParam, limit: 100 }, header: { 'X-Space-Id': props.space } },
  })).data!,
  getNextPageParam: page => page.nextCursor ?? undefined,
});
const items = computed(() => evidence.data.value?.pages.flatMap(page => page.items).filter(item => item.operationId === props.operationId) ?? []);
</script>
<template>
  <section aria-label="固定版本产物证据">
    <p>下载固定于下列版本；内容封存与功能验证分开显示。</p>
    <p v-if="evidence.isFetching.value" role="status">正在读取产物证据…</p>
    <p v-if="evidence.isError.value" role="alert">证据暂不可用；不会因此重新执行工具。<button @click="evidence.refetch()">重新读取</button></p>
    <ul><li v-for="item in items" :key="item.id">
      <a :href="`/api/v1/artifacts/${encodeURIComponent(item.id)}/download`" download>{{ item.title }} · 下载固定版本</a>
      <button @click="preview = item">预览固定版本</button>
      <small>版本 {{ item.versionId }} · {{ item.bytes }} B · SHA256 {{ item.sha256 }}</small>
      <p>内容已封存；功能验证：{{ item.testStatus === 'PASS' ? '通过' : item.testStatus === 'FAIL' ? '失败' : '未运行' }}</p>
    </li></ul>
    <p v-if="!items.length && !evidence.isFetching.value && !evidence.hasNextPage.value">此操作暂无已封存产物。</p>
    <button v-if="evidence.hasNextPage.value" :disabled="evidence.isFetchingNextPage.value" @click="evidence.fetchNextPage()">读取更多产物记录</button>
    <FilePreviewPane v-if="preview" :key="`${actor}:${space}:${preview.id}`" :file-id="preview.fileId" :version-id="preview.versionId" :name="preview.title" :user-id="actor" :space-id="space" historical @close="preview = undefined" />
  </section>
</template>
<style scoped>
section, li, small { min-width: 0; overflow-wrap: anywhere; } small { display: block; }
</style>
