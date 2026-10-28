<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import ToolCard from './ToolCard.vue';

const props = defineProps<{ actor: string; space: string; taskId: string; taskVersion: number }>();
const cursor = ref<string>();
watch(() => props.taskId, () => { cursor.value = undefined; });
const query = useQuery({
  queryKey: computed(() => ['chat', props.actor, props.space, 'tool-operations', props.taskId, props.taskVersion, cursor.value]),
  enabled: computed(() => Boolean(props.actor && props.space && props.taskId)),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/tasks/{id}/operations', {
    signal, params: { path: { id: props.taskId }, query: { limit: 20, ...(cursor.value ? { cursor: cursor.value } : {}) }, header: { 'X-Space-Id': props.space } },
  })).data!,
  refetchInterval: current => {
    const failure = current.state.error;
    if (failure instanceof ApiError && [401, 403, 404].includes(failure.problem.status)) return false;
    return current.state.data?.items.some(item => ['PREPARED', 'RUNNING'].includes(item.status)) ? 2000 : false;
  },
});
const items = computed(() => [...(query.data.value?.items ?? [])].reverse());
</script>

<template>
  <section aria-label="任务工具操作" :aria-busy="query.isFetching.value">
    <p v-if="query.isPending.value" role="status">正在读取工具操作…</p>
    <p v-if="query.isError.value" role="alert">暂时无法读取工具操作。已有任务不会重新提交。</p>
    <button v-if="query.isError.value" type="button" @click="query.refetch()">重新读取操作</button>
    <ToolCard v-for="operation in items" :key="operation.operationId" :operation="operation" :actor="actor" :space="space" />
    <div v-if="query.data.value?.nextCursor || cursor" class="chat-actions">
      <button v-if="query.data.value?.nextCursor" type="button" @click="cursor = query.data.value.nextCursor ?? undefined">读取更早操作</button>
      <button v-if="cursor" type="button" @click="cursor = undefined">返回最新操作</button>
    </div>
  </section>
</template>
