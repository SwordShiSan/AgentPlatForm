<script setup lang="ts">
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';

const props = defineProps<{ actor: string; space: string; taskId: string; version: number; interrupted: boolean }>();
const key = computed(() => ['chat', props.actor, props.space, 'recovery', props.taskId, props.version]);
const recovery = useQuery({
  queryKey: key,
  enabled: computed(() => !!props.actor && !!props.space && !!props.taskId),
  queryFn: async ({ queryKey, signal }) => (await api.GET('/api/v1/tasks/{id}/recovery', {
    signal, params: { path: { id: String(queryKey[4]) }, header: { 'X-Space-Id': String(queryKey[2]) } },
  })).data!,
  refetchInterval: query => {
    const error = query.state.error;
    if (error instanceof ApiError && [401, 403, 404].includes(error.problem.status)) return false;
    return ['QUEUED', 'RESTORING'].includes(query.state.data?.stage ?? '') ? 2000 : false;
  },
});
const label = computed(() => {
  const value = recovery.data.value;
  if (!value || value.taskId !== props.taskId) return '';
  if (value.stage === 'QUEUED') return '恢复已排队，正在等待执行槽；无需重新发送消息。';
  if (value.stage === 'RESTORING') return '正在恢复原任务，已提交的结果不会重复执行。';
  if (value.stage === 'COMPLETED' && value.revision > 0) return '原任务已恢复完成。';
  if (value.eligible) return '执行者已停止，可联系运维校验并恢复原任务；恢复前仍需核验状态完整性。';
  if (!props.interrupted) return '';
  switch (value.reasonCode) {
    case 'EXECUTION_STOP_UNCONFIRMED':
    case 'EXECUTION_STOP_NOT_CONFIRMED': return '正在等待执行者停止确认，暂不能恢复。';
    case 'RECOVERY_UNKNOWN_EFFECT': return '存在未知调用或副作用，须先核对账本；此任务不会自动重放。';
    case 'RECOVERY_DEADLINE_EXCEEDED': return '原任务执行期限已过，不再发起模型调用。';
    case 'MODEL_CALL_LIMIT': return '原任务已达到调用上限，不能通过恢复重新计算额度。';
    case 'RECOVERY_CONVERSATION_ADVANCED': return '此后已有独立新消息，旧任务不能再恢复。';
    case 'CREDENTIAL_REVOKED':
    case 'RESOURCE_NOT_FOUND': return '原任务权限或所用凭据已不可用，不能恢复。';
    case 'BUNDLE_INCOMPATIBLE': return '原任务运行版本不兼容，不能恢复；请提供诊断编号联系运维。';
    case 'CHECKPOINT_HASH_MISMATCH':
    case 'CHECKPOINT_STATE_INVALID':
    case 'CHECKPOINT_STATE_SCOPE_MISMATCH':
    case 'CHECKPOINT_LEDGER_ADVANCED':
    case 'CHECKPOINT_ATTEMPT_MISMATCH': return '保存的状态未通过完整性或一致性校验，不能恢复；请提供诊断编号联系运维。';
    case 'CHECKPOINT_NOT_COMMITTED': return '没有可恢复的已提交状态；请提供诊断编号联系运维。';
    default: return '';
  }
});
</script>

<template>
  <aside v-if="label" class="intro" aria-label="任务恢复状态" data-testid="recovery-notice" role="status">
    <p>{{ label }}</p>
  </aside>
  <p v-else-if="interrupted && recovery.isError.value" role="alert">暂时无法读取恢复状态，已有消息已保留。</p>
</template>
