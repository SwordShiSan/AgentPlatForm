<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton } from 'naive-ui';
import { api } from '../../shared/api/client';
import FilePreviewPane from './FilePreviewPane.vue';
const props = defineProps<{ fileId: string; name?: string; userId: string; spaceId: string; disabled?: boolean }>();
const emit = defineEmits<{ restore: [string] }>();
const cursor = ref<string>(); const preview = ref<string>();
watch(() => `${props.userId}:${props.spaceId}:${props.fileId}`, () => { cursor.value = undefined; preview.value = undefined; });
const history = useQuery({ queryKey: computed(() => ['file-history', props.userId, props.spaceId, props.fileId, cursor.value]),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/files/{id}/versions', {
    signal, params: { path: { id: props.fileId }, query: { cursor: cursor.value, limit: 100 }, header: { 'X-Space-Id': props.spaceId } },
  })).data!,
});
</script>
<template>
  <section aria-label="文件历史版本">
    <h4>历史版本</h4><p>此处只读查看固定版本；恢复会创建新版本，不改写历史。</p>
    <p v-if="history.isFetching.value" role="status">正在读取版本…</p>
    <p v-if="history.isError.value" role="alert">历史暂不可用。<NButton @click="history.refetch()">重新读取</NButton></p>
    <ul><li v-for="version in history.data.value?.items ?? []" :key="version.id">
      <p>{{ new Date(version.createdAt).toLocaleString() }} · {{ version.bytes }} B <span v-if="version.sourceVersionId">· 来自历史恢复</span></p>
      <small>版本 {{ version.id }} · SHA256 {{ version.sha256 }}</small>
      <NButton @click="preview = version.id">只读查看</NButton>
      <a :href="`/api/v1/files/${encodeURIComponent(fileId)}/content?versionId=${encodeURIComponent(version.id)}`" download>下载此版本</a>
      <NButton :disabled="disabled" @click="emit('restore', version.id)">恢复此版本</NButton>
    </li></ul>
    <NButton v-if="history.data.value?.nextCursor" @click="cursor = history.data.value.nextCursor ?? undefined">下一页历史</NButton>
    <NButton v-if="cursor" @click="cursor = undefined">返回最新历史</NButton>
    <FilePreviewPane v-if="preview" :key="`${userId}:${spaceId}:${fileId}:${preview}`" :file-id="fileId" :version-id="preview" :name="name ?? '历史版本'" :user-id="userId" :space-id="spaceId" historical @close="preview = undefined" />
  </section>
</template>
<style scoped>
section, li { min-width: 0; overflow-wrap: anywhere; } pre { white-space: pre-wrap; max-height: 24rem; overflow: auto; }
small { display: block; overflow-wrap: anywhere; } a { margin-inline: .5rem; }
</style>
