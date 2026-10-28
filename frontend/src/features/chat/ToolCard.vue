<script setup lang="ts">
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import type { components } from '../../shared/api/generated/http';
import { api } from '../../shared/api/client';

type Operation = components['schemas']['TaskToolOperation'];
const props = defineProps<{ operation: Operation; actor: string; space: string }>();
const labels: Record<Operation['phase'], string> = {
  PREPARED: '准备操作', WAITING_APPROVAL: '等待批准', ENVIRONMENT_PREPARING: '准备执行环境', RUNNING: '执行中',
  STOPPING: '正在确认停止', SEALING: '封存文件版本', SUCCEEDED: '已完成', FAILED: '执行失败', UNKNOWN: '结果待核对', DENIED: '已拒绝',
};
const result = useQuery({
  queryKey: computed(() => ['chat', props.actor, props.space, 'tool-result', props.operation.operationId, props.operation.status]),
  enabled: computed(() => Boolean(props.actor && props.space && props.operation.resultAvailable)),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/operations/{id}/result', {
    signal, params: { path: { id: props.operation.operationId }, header: { 'X-Space-Id': props.space } },
  })).data!,
});
const download = (file: components['schemas']['WorkspaceToolArtifact']) =>
  `/api/v1/files/${encodeURIComponent(file.fileId)}/content?versionId=${encodeURIComponent(file.versionId)}`;
</script>

<template>
  <section class="interaction-card tool-card" aria-label="工作区操作" data-testid="tool-card">
    <h3>{{ operation.summary }}</h3>
    <p role="status">{{ labels[operation.phase] }}</p>
    <template v-if="operation.review?.kind === 'SHELL'">
      <p>工作目录：{{ operation.review.cwd || '工作区根目录' }}</p>
      <details><summary>查看原命令与影响范围</summary><pre>{{ operation.review.command }}</pre>
        <p>命令可以创建、修改或删除整个绑定工作区内的文件；执行结束后临时进程和系统安装项不保留。</p>
      </details>
    </template>
    <p v-else-if="operation.review?.path">目标：{{ operation.review.path }}</p>
    <p v-if="operation.status === 'UNKNOWN'">尚未确认完整执行结果。平台保留相关资源和预留，不会自动重放此操作。</p>
    <p v-if="operation.errorCode">错误编号：{{ operation.errorCode }}</p>
    <p v-if="operation.traceId">诊断编号：{{ operation.traceId }}</p>
    <p v-if="result.isFetching.value" role="status">正在读取已保存的结果…</p>
    <p v-if="result.isError.value" role="alert">暂时无法读取结果。操作不会因此重新执行。</p>
    <button v-if="result.isError.value" type="button" @click="result.refetch()">重新读取结果</button>
    <template v-if="result.data.value">
      <p class="chat-text">{{ result.data.value.summary }}</p>
      <p v-if="result.data.value.exitCode !== null">退出码：{{ result.data.value.exitCode }}</p>
      <p v-if="result.data.value.truncated">输出超过显示上限，已截断；截断不代表文件内容被截断。</p>
      <details v-if="result.data.value.stdout || result.data.value.stderr"><summary>查看执行输出</summary>
        <h4 v-if="result.data.value.stdout">标准输出</h4><pre v-if="result.data.value.stdout">{{ result.data.value.stdout }}</pre>
        <h4 v-if="result.data.value.stderr">错误输出</h4><pre v-if="result.data.value.stderr">{{ result.data.value.stderr }}</pre>
      </details>
      <ul v-if="result.data.value.files.length" aria-label="已封存的文件版本">
        <li v-for="file in result.data.value.files" :key="file.versionId"><a :href="download(file)" download>{{ file.path }} · 下载此版本</a><small>SHA256 {{ file.sha256 }}</small></li>
      </ul>
    </template>
    <small>操作编号 {{ operation.operationId }}</small>
  </section>
</template>

<style scoped>
.tool-card { min-width: 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow: auto; }
small { display: block; overflow-wrap: anywhere; }
li { margin-block: .75rem; }
</style>
